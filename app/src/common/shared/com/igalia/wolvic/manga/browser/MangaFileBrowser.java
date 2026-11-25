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
 * The widget is dumb on purpose: the host loads entries (network work stays in
 * the existing SMB / 115 code paths) and this class only sorts and draws them.
 * That keeps the rewrite from touching the parts of the app that already work.
 */
public class MangaFileBrowser extends LinearLayout {

    /** How many columns each display mode uses. */
    private static final int GRID_SPAN = 4;
    private static final int COMPACT_SPAN = 2;

    private static final String PREFS = "manga_browser_prefs";
    private static final String KEY_SORT = "sort_key";
    private static final String KEY_ASC = "sort_asc";
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

    public interface Callback {
        /** A file was tapped. */
        void onOpen(Entry entry);

        /** A folder (tile or breadcrumb) was tapped; host moves there. */
        void onNavigate(String path);

        /** Host-provided back action (may be null at the root). */
        default void onBack() { }

        /** User cycled the sort mode; host should persist it to settings. */
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
    private TextView mSortButton;
    private TextView mModeButton;
    private TextView mSettingsButton;
    private LinearLayout mCrumbs;
    private View mCrumbScroll;

    private Callback mCallback;
    @Nullable private Runnable mSettingsClick;
    private String mTitleText = "文件";
    private List<Crumb> mCrumbList = new ArrayList<>();
    /** Matches MangaSettings.SORT_MODE exactly (0 name asc .. 3 date asc). */
    private int mSortMode = 0;
    private boolean mVideoFirst;
    private boolean mCompact;
    private boolean mCanGoBack;

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
        mSortButton = findViewById(R.id.browserSort);
        mModeButton = findViewById(R.id.browserMode);
        mSettingsButton = findViewById(R.id.browserSettings);
        mCrumbs = findViewById(R.id.browserCrumbs);
        mCrumbScroll = findViewById(R.id.browserCrumbScroll);

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mSortMode = prefs.getInt(KEY_SORT, 0);
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
        mSortButton.setOnClickListener(v -> {
            // Same 4-state cycle as the settings screen, so the two can never
            // disagree: name asc / name desc / date new / date old.
            mSortMode = SortUtil.next(mSortMode);
            persistSort();
            refreshSortLabel();
            sortAndRender();
            if (mCallback != null) {
                mCallback.onSortChanged(mSortMode);
            }
        });
        mSettingsButton.setOnClickListener(v -> {
            if (mSettingsClick != null) {
                mSettingsClick.run();
            }
        });
        mModeButton.setOnClickListener(v -> {
            mCompact = !mCompact;
            getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_MODE, mCompact ? 1 : 0).apply();
            applyLayoutManager();
            refreshModeLabel();
            mAdapter.notifyDataSetChanged();
        });

        refreshSortLabel();
        refreshModeLabel();
    }

    private void applyLayoutManager() {
        int span = mCompact ? COMPACT_SPAN : GRID_SPAN;
        GridLayoutManager lm = new GridLayoutManager(getContext(), span);
        lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return 1;
            }
        });
        mList.setLayoutManager(lm);
    }

    private void refreshSortLabel() {
        String[] labels = {"名称 ↑", "名称 ↓", "日期 ↓", "日期 ↑"};
        mSortButton.setText(labels[Math.max(0, Math.min(3, mSortMode))]);
    }

    private void refreshModeLabel() {
        mModeButton.setText(mCompact ? "▤" : "▦");
    }

    private void persistSort() {
        getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_SORT, mSortMode)
                .apply();
    }

    public void setCallback(@Nullable Callback callback) {
        mCallback = callback;
    }

    /** Mirrors MangaSettings.SORT_MODE / SORT_VIDEO_FIRST into the browser. */
    public void setSortConfig(int sortMode, boolean videoFirst) {
        mSortMode = Math.max(0, Math.min(3, sortMode));
        mVideoFirst = videoFirst;
        refreshSortLabel();
        if (!mSorted.isEmpty()) {
            sortAndRender();
        }
    }

    /** Opens the host settings screen; null hides nothing but does nothing. */
    public void setOnSettingsClick(@Nullable Runnable action) {
        mSettingsClick = action;
    }

    /** True when the host can navigate to a parent folder. */
    public void setCanGoBack(boolean canGoBack) {
        mCanGoBack = canGoBack;
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
        //   1) folders first
        //   2) videos before images when "视频置顶" is on
        //   3) the configured name/date order inside each group
        // so ticking the setting actually changes this listing.
        final Comparator<Entry> cmp = SortUtil.listingComparator(mSortMode, mVideoFirst,
                e -> e.directory,
                e -> e.video,
                e -> e.name,
                e -> e.modifiedMs);
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

    // ------------------------------------------------------------------ items

    private class Adapter extends RecyclerView.Adapter<Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.manga_browser_item, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Entry entry = mSorted.get(position);
            holder.bind(entry);
        }

        @Override
        public int getItemCount() {
            return mSorted.size();
        }
    }

    private class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView glyph;
        final TextView name;
        final TextView meta;
        final TextView badge;
        final FrameLayout thumbWrap;

        Holder(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.itemThumb);
            glyph = itemView.findViewById(R.id.itemGlyph);
            name = itemView.findViewById(R.id.itemName);
            meta = itemView.findViewById(R.id.itemMeta);
            badge = itemView.findViewById(R.id.itemBadge);
            thumbWrap = (FrameLayout) thumb.getParent();

            itemView.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos >= mSorted.size()) {
                    return;
                }
                Entry entry = mSorted.get(pos);
                if (mCallback == null) {
                    return;
                }
                if (entry.directory) {
                    mCallback.onNavigate(entry.path);
                } else {
                    mCallback.onOpen(entry);
                }
            });
            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                itemView.setBackgroundColor(hasFocus ? 0xFF252B2B : 0xFF1A2120);
                v.setScaleX(hasFocus ? 1.04f : 1f);
                v.setScaleY(hasFocus ? 1.04f : 1f);
            });
        }

        void bind(Entry entry) {
            name.setText(entry.name);
            meta.setText(entryMeta(entry));

            // Compact mode: shorter tile so many rows fit on the panel.
            ViewGroup.LayoutParams lp = thumbWrap.getLayoutParams();
            lp.height = dp(mCompact ? 74 : 118);
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
