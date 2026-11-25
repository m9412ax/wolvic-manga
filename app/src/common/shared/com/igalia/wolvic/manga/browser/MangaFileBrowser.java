/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.igalia.wolvic.R;
import com.igalia.wolvic.manga.MangaSettings;
import com.igalia.wolvic.manga.SortUtil;
import com.igalia.wolvic.ui.adapters.MangaCoverAdapter;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One file browser for every source (local disk, SMB share, 115 cloud).
 *
 * It replaces the old "one Button per row" rendering with the layout language
 * of NextPlayer (the open-source player this fork takes its playback stack
 * from): dark surface, teal accent, rounded thumbnail tiles, a real breadcrumb
 * trail, sortable grid/list modes and a summary line.
 *
 * The header only keeps ← / title / ⚙. Sort, layout, folder-first, video-first
 * and thumbnail ratio all live in the floating quick sheet (see
 * {@link MangaQuickSheet}) so the listing itself gets the whole page.
 */
public class MangaFileBrowser extends LinearLayout {

    private static final String PREFS = "manga_browser_prefs";
    private static final String KEY_MODE = "view_mode";

    /** A single row in the browser. */
    public static class Entry {
        public String name = "";
        public String path = "";
        /** Optional secondary line (e.g. "3 项" for folders). */
        public String meta = "";
        public boolean directory;
        public boolean video;
        public long size;
        public long modifiedMs;
        /** Local image used as a thumbnail; null for remote entries. */
        @Nullable public File thumb;
        /** Text shown on the thumbnail when there is no image. */
        @Nullable public String glyph;
        /** Corner badge, e.g. "▶" or "12P". */
        @Nullable public String badge;
        /** Original object, handed back to the host on click. */
        @Nullable public Object tag;

        public Entry(String name, String path, boolean directory) {
            this.name = name == null ? "" : name;
            this.path = path == null ? "" : path;
            this.directory = directory;
            this.glyph = directory ? "▣" : "▤";
        }
    }

    /** One clickable step of the breadcrumb trail. */
    public static class Crumb {
        public final String label;
        public final String path;

        public Crumb(String label, String path) {
            this.label = label == null ? "" : label;
            this.path = path == null ? "" : path;
        }
    }

    /** One extra header command (默认位置 / 加入书架 / 开始阅读 / 继续阅读). */
    public static class Action {
        public final String label;
        public final Runnable run;

        public Action(String label, Runnable run) {
            this.label = label == null ? "" : label;
            this.run = run;
        }
    }

    public interface Callback {
        /** A file was tapped. */
        void onOpen(Entry entry);

        /** A folder (tile or breadcrumb) was tapped; host moves there. */
        void onNavigate(String path);

        /** Host-provided back action (may be null at the root). */
        default void onBack() { }

        /** User changed the sort mode; the browser already persisted it. */
        default void onSortChanged(int sortMode) { }
    }

    private final List<Entry> mEntries = new ArrayList<>();
    private final List<Entry> mSorted = new ArrayList<>();
    private final ExecutorService mThumbs = Executors.newFixedThreadPool(3);
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private RecyclerView mList;
    private Adapter mAdapter;
    private TextView mTitle;
    private TextView mSummary;
    private TextView mStatus;
    private TextView mBack;
    private TextView mSettingsButton;
    private LinearLayout mActionBar;
    private LinearLayout mCrumbs;
    private View mCrumbScroll;

    private Callback mCallback;
    @Nullable private ViewGroup mSheetHost;
    @Nullable private MangaQuickSheet mSheet;
    private String mTitleText = "文件";
    private List<Crumb> mCrumbList = new ArrayList<>();
    /** Matches MangaSettings.SORT_MODE (0..5). */
    private int mSortMode = SortUtil.MODE_NAME_ASC;
    private boolean mVideoFirst;
    private boolean mFolderFirst = true;
    private boolean mCompact;
    /** 0 = 16:9, 1 = 3:4 (default), 2 = 9:16. */
    private int mThumbRatio = 1;
    /** Target tile width in dp; drives the column count. */
    private int mThumbSize = 160;

    public MangaFileBrowser(Context context) {
        super(context);
        init(context);
    }

    public MangaFileBrowser(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF0E1514);
        LayoutInflater.from(context).inflate(R.layout.manga_browser, this, true);

        mList = findViewById(R.id.browserList);
        mTitle = findViewById(R.id.browserTitle);
        mSummary = findViewById(R.id.browserSummary);
        mStatus = findViewById(R.id.browserStatus);
        mBack = findViewById(R.id.browserBack);
        mSettingsButton = findViewById(R.id.browserSettings);
        mActionBar = findViewById(R.id.browserActions);
        mCrumbs = findViewById(R.id.browserCrumbs);
        mCrumbScroll = findViewById(R.id.browserCrumbScroll);

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mCompact = prefs.getInt(KEY_MODE, 0) == 1;

        mAdapter = new Adapter();
        mList.setAdapter(mAdapter);
        applyLayoutManager();
        mList.setHasFixedSize(false);

        mBack.setOnClickListener(v -> {
            if (mCallback != null) {
                mCallback.onBack();
            }
        });
        mSettingsButton.setOnClickListener(v -> openQuickSheet());
    }

    private void applyLayoutManager() {
        int span = mCompact ? 1 : gridSpan();
        GridLayoutManager lm = new GridLayoutManager(getContext(), span);
        lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return 1;
            }
        });
        mList.setLayoutManager(lm);
    }

    public void setCallback(@Nullable Callback callback) {
        mCallback = callback;
    }

    /**
     * Shelf actions shown in their own framed group at the top right, in the
     * same row as the ⚙ button. Passing an empty list hides the frame.
     */
    public void setHeaderActions(@Nullable List<Action> actions) {
        mActionBar.removeAllViews();
        if (actions == null || actions.isEmpty()) {
            mActionBar.setVisibility(GONE);
            return;
        }
        mActionBar.setVisibility(VISIBLE);
        for (final Action action : actions) {
            TextView button = new TextView(getContext());
            button.setText(action.label);
            button.setTextSize(13f);
            button.setSingleLine(true);
            button.setTextColor(0xFFDCE4E2);
            button.setPadding(dp(10), dp(6), dp(10), dp(6));
            button.setBackgroundResource(R.drawable.manga_pill_bg);
            button.setOnClickListener(v -> {
                if (action.run != null) {
                    action.run.run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(4);
            mActionBar.addView(button, lp);
        }
    }

    /** Hosts the floating quick sheet (the panel's page container). */
    public void setSheetHost(@Nullable ViewGroup host) {
        mSheetHost = host;
    }

    private ViewGroup sheetHost() {
        if (mSheetHost != null) {
            return mSheetHost;
        }
        View root = getRootView();
        View container = root.findViewById(R.id.pageContainer);
        if (container instanceof ViewGroup) {
            return (ViewGroup) container;
        }
        ViewParent parent = getParent();
        return parent instanceof ViewGroup ? (ViewGroup) parent : this;
    }

    /** Mirrors MangaSettings.SORT_MODE / SORT_VIDEO_FIRST into the browser. */
    public void setSortConfig(int sortMode, boolean videoFirst) {
        setSortConfig(sortMode, videoFirst, true);
    }

    public void setSortConfig(int sortMode, boolean videoFirst, boolean folderFirst) {
        mSortMode = Math.max(0, Math.min(SortUtil.MODE_COUNT - 1, sortMode));
        mVideoFirst = videoFirst;
        mFolderFirst = folderFirst;
        if (!mSorted.isEmpty()) {
            sortAndRender();
        }
    }

    /** Thumbnail aspect for the grid: 0 = 16:9, 1 = 3:4, 2 = 9:16. */
    public void setThumbRatio(int ratio) {
        mThumbRatio = Math.max(0, Math.min(2, ratio));
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    /** Columns are derived from the configured tile width, so shrinking the
     *  thumbnails genuinely fits more items per row. */
    private int gridSpan() {
        int widthDp = listWidthDp();
        int target = Math.max(90, mThumbSize);
        return Math.max(2, Math.min(10, widthDp / target));
    }

    private int listWidthDp() {
        int px = mList.getWidth();
        if (px <= 0) {
            px = getResources().getDisplayMetrics().widthPixels;
        }
        float density = getResources().getDisplayMetrics().density;
        return Math.max(200, (int) (px / (density <= 0 ? 1f : density)));
    }

    public void setThumbSize(int dp) {
        mThumbSize = Math.max(90, Math.min(280, dp));
        applyLayoutManager();
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    private int gridThumbHeight() {
        int listWidth = mList.getWidth();
        if (listWidth <= 0) {
            listWidth = getResources().getDisplayMetrics().widthPixels;
        }
        int cardWidth = Math.max(dp(60), listWidth / Math.max(1, gridSpan()) - dp(16));
        float ratio = mThumbRatio == 0 ? 9f / 16f : mThumbRatio == 2 ? 16f / 9f : 4f / 3f;
        return Math.round(cardWidth * ratio);
    }

    /** Opens the host settings screen; null hides nothing but does nothing. */
    public void setOnSettingsClick(@Nullable Runnable action) {
        mSettingsClick = action;
    }

    @Nullable private Runnable mSettingsClick;

    /** True when the host can navigate to a parent folder. */
    public void setCanGoBack(boolean canGoBack) {
        // Always visible: dimmed when there is nowhere to go, so the header
        // layout does not jump between folders.
        mBack.setVisibility(VISIBLE);
        mBack.setAlpha(canGoBack ? 1f : 0.3f);
    }

    /** Replaces the whole listing. Call from the UI thread. */
    public void setData(@Nullable List<Entry> entries,
                        @Nullable String title,
                        @Nullable List<Crumb> crumbs,
                        @Nullable String status) {
        mEntries.clear();
        if (entries != null) {
            mEntries.addAll(entries);
        }
        if (title != null) {
            mTitleText = title;
        }
        mTitle.setText(mTitleText);
        mCrumbList = crumbs == null ? new ArrayList<Crumb>() : new ArrayList<>(crumbs);
        buildCrumbs();
        if (TextUtils.isEmpty(status)) {
            mStatus.setVisibility(GONE);
        } else {
            mStatus.setText(status);
            mStatus.setVisibility(VISIBLE);
        }
        sortAndRender();
    }

    private void buildCrumbs() {
        mCrumbs.removeAllViews();
        if (mCrumbList.isEmpty()) {
            mCrumbScroll.setVisibility(GONE);
            return;
        }
        mCrumbScroll.setVisibility(VISIBLE);
        for (int i = 0; i < mCrumbList.size(); i++) {
            final Crumb crumb = mCrumbList.get(i);
            boolean last = i == mCrumbList.size() - 1;
            TextView step = new TextView(getContext());
            step.setText(crumb.label);
            step.setTextSize(13f);
            step.setSingleLine(true);
            step.setEllipsize(TextUtils.TruncateAt.END);
            step.setMaxWidth(dp(220));
            step.setTextColor(last ? 0xFF80D5D2 : 0xFFBEC9C7);
            step.setPadding(dp(6), dp(4), dp(6), dp(4));
            step.setClickable(!last);
            step.setFocusable(!last);
            if (!last) {
                step.setOnClickListener(v -> {
                    if (mCallback != null) {
                        mCallback.onNavigate(crumb.path);
                    }
                });
            }
            mCrumbs.addView(step);
            if (!last) {
                TextView sep = new TextView(getContext());
                sep.setText("›");
                sep.setTextSize(13f);
                sep.setTextColor(0xFF5A6866);
                sep.setPadding(dp(2), dp(4), dp(2), dp(4));
                mCrumbs.addView(sep);
            }
        }
    }

    private void sortAndRender() {
        mSorted.clear();
        mSorted.addAll(mEntries);
        // Shared with the settings screen: SortUtil enforces
        //   1) folders first (unless the quick sheet turned it off)
        //   2) videos before images when "视频置顶" is on
        //   3) the configured name/date/size order inside each group
        final boolean folderFirst = mFolderFirst;
        final boolean videoFirst = mVideoFirst;
        final Comparator<Entry> base = SortUtil.comparator(mSortMode,
                (Entry e) -> e.name,
                (Entry e) -> e.modifiedMs,
                (Entry e) -> Math.max(0, e.size));
        final Comparator<Entry> cmp = (a, b) -> {
            if (folderFirst && a.directory != b.directory) {
                return a.directory ? -1 : 1;
            }
            if (videoFirst && !a.directory && !b.directory && a.video != b.video) {
                return a.video ? -1 : 1;
            }
            return base.compare(a, b);
        };
        Collections.sort(mSorted, cmp);
        mAdapter.notifyDataSetChanged();
        updateSummary();
    }

    private void updateSummary() {
        int folders = 0;
        int files = 0;
        long bytes = 0;
        for (Entry e : mSorted) {
            if (e.directory) {
                folders++;
            } else {
                files++;
                bytes += Math.max(0, e.size);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (folders == 0 && files == 0) {
            mSummary.setText("此目录为空");
            return;
        }
        sb.append(folders).append(" 个文件夹 · ").append(files).append(" 个文件");
        if (bytes > 0) {
            sb.append(" · ").append(readableSize(bytes));
        }
        mSummary.setText(sb.toString());
    }

    // ------------------------------------------------------------ quick sheet

    /** Floating 快捷设置 window: layout, sort, pinned groups, thumb ratio. */
    private void openQuickSheet() {
        final int key = SortUtil.keyOf(mSortMode);
        final boolean ascending = SortUtil.ascendingOf(mSortMode);
        ViewerDraft draft = new ViewerDraft(this);

        MangaQuickSheet sheet = MangaQuickSheet.open(sheetHost(),
                "快捷设置",
                s -> {
                    s.section("媒体布局");
                    s.segments(new String[]{"列表", "网格"},
                            draft.compact ? 0 : 1, i -> draft.compact = i == 0);

                    s.divider();
                    s.section("排序");
                    s.roundChips(new String[]{"T", "◷", "▤"},
                            new String[]{"标题", "日期", "大小"},
                            SortUtil.keyOf(draft.sortMode), i -> {
                                draft.sortMode = SortUtil.modeOf(i,
                                        SortUtil.ascendingOf(draft.sortMode));
                            });
                    // Direction labels follow the key: A-Z for names, old/new
                    // for dates, small/large for sizes.
                    int draftKey = SortUtil.keyOf(draft.sortMode);
                    String[] dirs = draftKey == 0
                            ? new String[]{"A → Z", "Z → A"}
                            : draftKey == 1
                            ? new String[]{"旧 → 新", "新 → 旧"}
                            : new String[]{"小 → 大", "大 → 小"};
                    s.segments(dirs,
                            SortUtil.ascendingOf(draft.sortMode) ? 0 : 1, i -> {
                                draft.sortMode = SortUtil.modeOf(
                                        SortUtil.keyOf(draft.sortMode), i == 0);
                            });

                    s.divider();
                    s.section("置顶");
                    s.toggleChips(new String[]{"文件夹置顶", "视频置顶"},
                            new boolean[]{draft.folderFirst, draft.videoFirst}, i -> {
                                if (i == 0) {
                                    draft.folderFirst = !draft.folderFirst;
                                } else {
                                    draft.videoFirst = !draft.videoFirst;
                                }
                            });

                    s.section("缩略图");
                    s.segments(new String[]{"16:9", "3:4", "9:16"},
                            draft.thumbRatio, i -> draft.thumbRatio = i);
                    s.caption("缩略图大小在「设置 · 来源」里统一调整（影响本地 / SMB / 115 / 扩展源）。");

                    // The header no longer carries a settings button, so the
                    // per-source config (SMB host, 115 login, WebDAV account)
                    // stays one tap away from this sheet.
                    if (mSettingsClick != null) {
                        s.divider();
                        s.section("更多");
                        s.toggleChipsRaw(new String[]{"⚙  本来源的设置 / 登录"},
                                new boolean[]{false}, i -> {
                                    Runnable action = mSettingsClick;
                                    s.dismiss();
                                    if (action != null) {
                                        post(action);
                                    }
                                });
                    }
                },
                new MangaQuickSheet.DismissListener() {
                    @Override
                    public void onConfirm() {
                        applyDraft(draft);
                    }
                });
        sheet.withFooter(false);
        mSheet = sheet;
    }

    /** Mutable copy so 取消 really cancels every switch made in the sheet. */
    private static final class ViewerDraft {
        boolean compact;
        boolean videoFirst;
        boolean folderFirst;
        int thumbRatio;
        int sortMode;

        ViewerDraft(MangaFileBrowser b) {
            compact = b.mCompact;
            videoFirst = b.mVideoFirst;
            folderFirst = b.mFolderFirst;
            thumbRatio = b.mThumbRatio;
            sortMode = b.mSortMode;
        }
    }

    private void applyDraft(ViewerDraft draft) {
        boolean layoutChanged = draft.compact != mCompact;
        boolean thumbChanged = draft.thumbRatio != mThumbRatio;
        boolean orderChanged = draft.sortMode != mSortMode
                || draft.videoFirst != mVideoFirst
                || draft.folderFirst != mFolderFirst;

        mCompact = draft.compact;
        mSortMode = draft.sortMode;
        mVideoFirst = draft.videoFirst;
        mFolderFirst = draft.folderFirst;
        mThumbRatio = draft.thumbRatio;

        getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_MODE, mCompact ? 1 : 0)
                .apply();

        // Persist into the ONE settings store so the 设置 page, the shelf and
        // every other source's browser show the same values afterwards.
        MangaSettings settings = MangaSettings.get(getContext());
        settings.put(MangaSettings.SORT_MODE, mSortMode);
        settings.put(MangaSettings.SORT_VIDEO_FIRST, mVideoFirst);
        settings.put(MangaSettings.SORT_FOLDER_FIRST, mFolderFirst);
        settings.put(MangaSettings.THUMB_RATIO, mThumbRatio);

        if (layoutChanged) {
            applyLayoutManager();
        }
        if (orderChanged) {
            sortAndRender();
        } else if (thumbChanged) {
            mAdapter.notifyDataSetChanged();
        }
        if (mCallback != null) {
            mCallback.onSortChanged(mSortMode);
        }
    }

    // ------------------------------------------------------------------ items

    private class Adapter extends RecyclerView.Adapter<BaseHolder> {

        @Override
        public int getItemViewType(int position) {
            // 0 = cover grid, 1 = true single-column list row.
            return mCompact ? 1 : 0;
        }

        @NonNull
        @Override
        public BaseHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == 1) {
                return new RowHolder(inflater.inflate(
                        R.layout.manga_browser_row, parent, false));
            }
            return new GridHolder(inflater.inflate(
                    R.layout.manga_browser_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull BaseHolder holder, int position) {
            holder.bind(mSorted.get(position));
        }

        @Override
        public int getItemCount() {
            return mSorted.size();
        }
    }

    private abstract class BaseHolder extends RecyclerView.ViewHolder {
        BaseHolder(@NonNull View itemView) {
            super(itemView);
            itemView.setOnClickListener(v -> openAt(getBindingAdapterPosition()));
            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                // Dim non-focused rows instead of repainting the background:
                // setBackgroundColor would square off the rounded card.
                v.setAlpha(hasFocus ? 1f : 0.86f);
                v.setScaleX(hasFocus ? 1.03f : 1f);
                v.setScaleY(hasFocus ? 1.03f : 1f);
            });
        }

        void openAt(int pos) {
            if (pos == RecyclerView.NO_POSITION || pos >= mSorted.size()
                    || mCallback == null) {
                return;
            }
            Entry entry = mSorted.get(pos);
            if (entry.directory) {
                mCallback.onNavigate(entry.path);
            } else {
                mCallback.onOpen(entry);
            }
        }

        abstract void bind(Entry entry);
    }

    /** Cover tile (grid mode). */
    private class GridHolder extends BaseHolder {
        final ImageView thumb;
        final TextView glyph;
        final TextView name;
        final TextView meta;
        final TextView badge;
        final FrameLayout thumbWrap;

        GridHolder(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.itemThumb);
            glyph = itemView.findViewById(R.id.itemGlyph);
            name = itemView.findViewById(R.id.itemName);
            meta = itemView.findViewById(R.id.itemMeta);
            badge = itemView.findViewById(R.id.itemBadge);
            thumbWrap = (FrameLayout) thumb.getParent();
        }

        @Override
        void bind(Entry entry) {
            name.setText(entry.name);
            meta.setText(entryMeta(entry));

            // Aspect ratio follows the setting: manga pages are portrait, so
            // the old hard-coded 16:9 strip cut most of the preview off.
            ViewGroup.LayoutParams lp = thumbWrap.getLayoutParams();
            lp.height = gridThumbHeight();
            thumbWrap.setLayoutParams(lp);

            badge.setVisibility(TextUtils.isEmpty(entry.badge) ? GONE : VISIBLE);
            if (!TextUtils.isEmpty(entry.badge)) {
                badge.setText(entry.badge);
            }

            if (entry.thumb != null && entry.thumb.isFile()) {
                glyph.setVisibility(GONE);
                thumb.setVisibility(VISIBLE);
                loadThumb(thumb, entry.thumb);
            } else {
                thumb.setImageDrawable(null);
                thumb.setTag(null);
                thumb.setVisibility(GONE);
                glyph.setVisibility(VISIBLE);
                glyph.setText(entry.glyph == null
                        ? (entry.directory ? "▣" : entry.video ? "▶" : "▤")
                        : entry.glyph);
                glyph.setTextColor(entry.video ? 0xFFF2B880 : 0xFF80D5D2);
            }
        }
    }

    /** Compact row (list mode): 48dp icon + two text lines. */
    private class RowHolder extends BaseHolder {
        final ImageView thumb;
        final TextView glyph;
        final TextView name;
        final TextView meta;
        final TextView badge;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.rowThumb);
            glyph = itemView.findViewById(R.id.rowGlyph);
            name = itemView.findViewById(R.id.rowName);
            meta = itemView.findViewById(R.id.rowMeta);
            badge = itemView.findViewById(R.id.rowBadge);
        }

        @Override
        void bind(Entry entry) {
            name.setText(entry.name);
            meta.setText(entryMeta(entry));
            // Row height follows the same size slider as the grid, so shrinking
            // the thumbnails also fits more rows on screen.
            ViewGroup.LayoutParams lp = itemView.getLayoutParams();
            lp.height = Math.max(dp(48), Math.round(mThumbSize * 0.42f
                    * getResources().getDisplayMetrics().density));
            itemView.setLayoutParams(lp);
            badge.setVisibility(TextUtils.isEmpty(entry.badge) ? GONE : VISIBLE);
            if (!TextUtils.isEmpty(entry.badge)) {
                badge.setText(entry.badge);
            }
            if (entry.thumb != null && entry.thumb.isFile()) {
                glyph.setVisibility(GONE);
                thumb.setVisibility(VISIBLE);
                loadThumb(thumb, entry.thumb);
            } else {
                thumb.setImageDrawable(null);
                thumb.setTag(null);
                thumb.setVisibility(GONE);
                glyph.setVisibility(VISIBLE);
                glyph.setText(entry.glyph == null
                        ? (entry.directory ? "▣" : entry.video ? "▶" : "▤")
                        : entry.glyph);
                glyph.setTextColor(entry.video ? 0xFFF2B880 : 0xFF80D5D2);
            }
        }
    }

    private String entryMeta(Entry entry) {
        if (!TextUtils.isEmpty(entry.meta)) {
            return entry.meta;
        }
        if (entry.directory) {
            return "文件夹";
        }
        StringBuilder sb = new StringBuilder();
        if (entry.size > 0) {
            sb.append(readableSize(entry.size));
        }
        if (entry.modifiedMs > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(formatDate(entry.modifiedMs));
        }
        return sb.length() == 0 ? "" : sb.toString();
    }

    private void loadThumb(ImageView view, File file) {
        final String key = file.getAbsolutePath() + "@" + file.lastModified();
        view.setTag(key);
        mThumbs.execute(() -> {
            Bitmap bitmap = MangaCoverAdapter.decodeScaled(file, dp(240), dp(240));
            if (bitmap == null) {
                return;
            }
            mMain.post(() -> {
                if (key.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                } else {
                    bitmap.recycle();
                }
            });
        });
    }

    // ------------------------------------------------------------------ utils

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    public static String readableSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.0f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    public static String formatDate(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(millis));
    }
}
