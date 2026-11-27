/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.adapters;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.igalia.wolvic.R;
import com.igalia.wolvic.manga.MangaImageDecoder;
import com.igalia.wolvic.manga.MangaReadingState;
import com.igalia.wolvic.manga.MangaSettings;
import com.igalia.wolvic.manga.MangaThumbCache;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bookshelf adapter. Two presentations share one data set:
 *
 * <ul>
 *   <li>icon view - cover tile, title, info line and a thin progress bar</li>
 *   <li>list view - a real single-column row (cover on the left, title / source
 *       info / progress on the right), matching the 历史 page instead of a
 *       stretched cover column</li>
 * </ul>
 *
 * Covers are decoded on a background thread, scaled to the configured budget
 * and cached in {@link MangaThumbCache}. Remote (SMB/115) covers arrive later
 * from the host through {@link #setCover(String, File)}.
 */
public class MangaCoverAdapter extends RecyclerView.Adapter<MangaCoverAdapter.CoverViewHolder> {

    /** Click callback. The folder is the manga root directory. */
    public interface OnMangaClickListener {
        void onMangaClick(@NonNull File folder);
        /** Called for remote (SMB/115) bookshelf entries that have no local
         *  folder. Default delegates with a dummy file for compatibility. */
        default void onRemoteMangaClick(@NonNull String remoteKey) { }
    }

    /** Selection changed (multi-select on the bookshelf). */
    public interface OnSelectionChanged {
        void onSelectionChanged(int selected, int total);
    }

    /** Long-press callback, e.g. to reset reading progress for a folder. */
    public interface OnMangaLongClickListener {
        void onMangaLongClick(@NonNull File folder);
    }

    /** One manga entry: a folder plus its discovered cover / video flag.
     *  Remote entries (SMB/115) have a non-null remoteKey and a null folder. */
    public static class MangaItem {
        @NonNull public final File folder;
        @NonNull public final String title;
        /** Mutable: a remote cover is attached when its download finishes. */
        @Nullable public File cover;
        public final boolean hasVideo;
        @Nullable public final String remoteKey;
        /** 0..100, -1 when this book was never opened. */
        public int percent = -1;
        /** Small info line ("12 章 · 已读 3 章", "SMB · 45%"). */
        public String meta = "";
        /** Corner tag: SMB / 115 / 图源. Null for local folders. */
        public String badge = "";
        /** {@link MangaReadingState#NONE} / READING / DONE. */
        public int state = MangaReadingState.NONE;
        /** True while the host is fetching a remote cover. */
        public boolean coverPending;

        public MangaItem(@NonNull File folder, @NonNull String title,
                         @Nullable File cover, boolean hasVideo) {
            this(folder, title, cover, hasVideo, null);
        }

        public MangaItem(@NonNull File folder, @NonNull String title,
                         @Nullable File cover, boolean hasVideo,
                         @Nullable String remoteKey) {
            this.folder = folder;
            this.title = title;
            this.cover = cover;
            this.hasVideo = hasVideo;
            this.remoteKey = remoteKey;
        }
    }

    private static final int TYPE_GRID = 0;
    private static final int TYPE_ROW = 1;
    private static final int COVER_TARGET_PX = 320;

    /** Card width in px, set by the host so covers keep a 3:4 shape. */
    private int mCardWidth;
    private final Context mContext;
    private final List<MangaItem> mItems = new ArrayList<>();
    private final OnMangaClickListener mListener;
    private final OnMangaLongClickListener mLongListener;
    private final Set<String> mSelected = new HashSet<>();
    private boolean mSelectionMode;
    @Nullable private OnSelectionChanged mSelectionListener;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    /** Cover decode budget from 设置 → 漫画 · 图源 → 封面解码尺寸. */
    private final int mTargetPx;
    /** Compact rows for the bookshelf list view. */
    private boolean mCompact = false;

    /** Card width in px; covers are drawn at a 3:4 ratio of it. */
    public void setCardWidth(int px) {
        if (mCardWidth != px) {
            mCardWidth = px;
            notifyDataSetChanged();
        }
    }

    public void setCompact(boolean compact) {
        if (mCompact != compact) {
            mCompact = compact;
            notifyDataSetChanged();
        }
    }

    public boolean isCompact() {
        return mCompact;
    }

    public void setSelectionListener(@Nullable OnSelectionChanged listener) {
        mSelectionListener = listener;
    }

    public boolean isSelectionMode() {
        return mSelectionMode;
    }

    /** Stable identity of an item, also used by the host for batch actions. */
    public static String keyOf(@NonNull MangaItem item) {
        return item.remoteKey != null ? item.remoteKey : item.folder.getAbsolutePath();
    }

    public void setSelectionMode(boolean on) {
        if (mSelectionMode == on) {
            return;
        }
        mSelectionMode = on;
        if (!on) {
            mSelected.clear();
        }
        notifyDataSetChanged();
        notifySelection();
    }

    public void toggleSelection(int position) {
        if (position < 0 || position >= mItems.size()) {
            return;
        }
        String key = keyOf(mItems.get(position));
        if (!mSelected.remove(key)) {
            mSelected.add(key);
        }
        notifyItemChanged(position);
        notifySelection();
    }

    public void selectAll(boolean select) {
        mSelected.clear();
        if (select) {
            for (MangaItem item : mItems) {
                mSelected.add(keyOf(item));
            }
        }
        notifyDataSetChanged();
        notifySelection();
    }

    public int selectedCount() {
        return mSelected.size();
    }

    public int totalCount() {
        return mItems.size();
    }

    /** Items currently ticked, in list order. */
    @NonNull
    public List<MangaItem> selectedItems() {
        List<MangaItem> out = new ArrayList<>();
        for (MangaItem item : mItems) {
            if (mSelected.contains(keyOf(item))) {
                out.add(item);
            }
        }
        return out;
    }

    /** Snapshot of the current data set (host uses it for cover prefetch). */
    @NonNull
    public List<MangaItem> items() {
        return new ArrayList<>(mItems);
    }

    @Nullable
    public MangaItem itemAt(int position) {
        return position >= 0 && position < mItems.size() ? mItems.get(position) : null;
    }

    /** Attaches a cover that arrived after the initial scan. */
    public void setCover(@Nullable String key, @Nullable File cover) {
        if (key == null) {
            return;
        }
        for (int i = 0; i < mItems.size(); i++) {
            MangaItem item = mItems.get(i);
            if (key.equals(keyOf(item))) {
                item.cover = cover;
                item.coverPending = false;
                notifyItemChanged(i);
                return;
            }
        }
    }

    private void notifySelection() {
        if (mSelectionListener != null) {
            mSelectionListener.onSelectionChanged(mSelected.size(), mItems.size());
        }
    }

    public MangaCoverAdapter(@NonNull Context context, @Nullable OnMangaClickListener listener,
                             @Nullable OnMangaLongClickListener longListener) {
        mContext = context;
        mListener = listener;
        mLongListener = longListener;
        int configured = MangaSettings.get(context).integer(MangaSettings.SOURCE_THUMB_PX);
        mTargetPx = configured > 0 ? configured : COVER_TARGET_PX;
    }

    public void update(@Nullable List<MangaItem> items) {
        mSelected.clear();
        mItems.clear();
        if (items != null) {
            mItems.addAll(items);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return mCompact ? TYPE_ROW : TYPE_GRID;
    }

    @NonNull
    @Override
    public CoverViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(mContext).inflate(
                viewType == TYPE_ROW ? R.layout.manga_shelf_row : R.layout.manga_cover_item,
                parent, false);
        return new CoverViewHolder(view, viewType == TYPE_ROW);
    }

    @Override
    public void onBindViewHolder(@NonNull CoverViewHolder holder, int position) {
        final MangaItem item = mItems.get(position);

        holder.title.setText(item.title);
        holder.meta.setText(item.meta == null || item.meta.isEmpty()
                ? (item.remoteKey != null ? "在线漫画" : "未开始阅读") : item.meta);
        if (holder.badge != null) {
            if (TextUtils.isEmpty(item.badge)) {
                holder.badge.setVisibility(View.GONE);
            } else {
                holder.badge.setVisibility(View.VISIBLE);
                holder.badge.setText(item.badge);
            }
        }

        // Grid tiles keep the 3:4 book shape of the real column width. Only
        // touch the layout params when the height actually changes: assigning
        // them on every bind requests a layout from inside a layout pass.
        if (!holder.isRow) {
            int targetPx = gridCoverHeight();
            ViewGroup.LayoutParams lp = holder.coverWrap.getLayoutParams();
            if (lp != null && lp.height != targetPx) {
                lp.height = targetPx;
                holder.coverWrap.setLayoutParams(lp);
            }
        }

        if (item.percent >= 0) {
            holder.progress.setVisibility(View.VISIBLE);
            holder.progress.setProgress(Math.max(0, Math.min(100, item.percent)));
            int tint = item.state == MangaReadingState.DONE ? 0xFF4CD98A
                    : item.state == MangaReadingState.READING ? 0xFF4C9AFF
                    : 0xFF80D5D2;
            if (holder.progress.getProgressDrawable() != null) {
                holder.progress.getProgressDrawable()
                        .setColorFilter(tint, PorterDuff.Mode.SRC_IN);
            }
        } else {
            holder.progress.setVisibility(View.GONE);
        }

        boolean selected = mSelectionMode && mSelected.contains(keyOf(item));
        holder.itemView.setBackgroundResource(selected
                ? R.drawable.manga_card_selected
                : item.state == MangaReadingState.DONE ? R.drawable.manga_card_done
                : item.state == MangaReadingState.READING ? R.drawable.manga_card_reading
                : R.drawable.manga_card_bg);
        if (holder.check != null) {
            holder.check.setVisibility(selected ? View.VISIBLE : View.GONE);
        }

        bindCover(holder, item);

        holder.itemView.setOnClickListener(v -> {
            if (mSelectionMode) {
                toggleSelection(holder.getBindingAdapterPosition());
                return;
            }
            if (mListener != null) {
                if (item.remoteKey != null) {
                    mListener.onRemoteMangaClick(item.remoteKey);
                } else {
                    mListener.onMangaClick(item.folder);
                }
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            // Long-press enters multi-select (VR users cannot aim at a tiny
            // checkbox). Progress reset moved to the selection bar's menu.
            if (!mSelectionMode) {
                setSelectionMode(true);
                toggleSelection(holder.getBindingAdapterPosition());
            } else {
                toggleSelection(holder.getBindingAdapterPosition());
            }
            return true;
        });
        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            v.setAlpha(hasFocus ? 1f : 0.9f);
        });
    }

    private int gridCoverHeight() {
        if (mCardWidth > 0) {
            // 3:4 portrait cover, the shape of a real manga book.
            return Math.round(mCardWidth * 1.34f);
        }
        int fallback = Math.round(180 * mContext.getResources().getDisplayMetrics().density);
        return Math.max(fallback, 1);
    }

    private void bindCover(@NonNull CoverViewHolder holder, @NonNull MangaItem item) {
        if (item.cover != null && item.cover.isFile()) {
            holder.glyph.setVisibility(View.GONE);
            holder.cover.setVisibility(View.VISIBLE);
            loadCover(holder.cover, item.cover);
            return;
        }
        holder.cover.setImageDrawable(null);
        holder.cover.setTag(null);
        holder.cover.setVisibility(View.GONE);
        holder.glyph.setVisibility(View.VISIBLE);
        holder.glyph.setText(item.coverPending ? "…" : item.hasVideo ? "▶" : "▣");
        holder.glyph.setTextColor(item.hasVideo ? 0xFFF2B880 : 0xFF80D5D2);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    static class CoverViewHolder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final View coverWrap;
        final TextView glyph;
        final TextView title;
        final TextView meta;
        final TextView badge;
        final TextView check;
        final ProgressBar progress;
        final boolean isRow;

        CoverViewHolder(@NonNull View itemView, boolean row) {
            super(itemView);
            isRow = row;
            cover = itemView.findViewById(R.id.shelfCover);
            coverWrap = itemView.findViewById(R.id.shelfCoverWrap);
            glyph = itemView.findViewById(R.id.shelfGlyph);
            title = itemView.findViewById(R.id.shelfTitle);
            meta = itemView.findViewById(R.id.shelfMeta);
            badge = itemView.findViewById(R.id.shelfBadge);
            check = itemView.findViewById(R.id.shelfCheck);
            progress = itemView.findViewById(R.id.shelfProgress);
        }
    }

    private void loadCover(@NonNull ImageView view, @Nullable File cover) {
        if (cover == null) {
            view.setImageDrawable(null);
            view.setTag(null);
            return;
        }
        final String path = cover.getAbsolutePath();
        if (path.equals(view.getTag())) {
            return; // already showing this cover (recycled row, no flicker)
        }
        view.setImageDrawable(null);
        // Tag guards against recycled views receiving a stale bitmap.
        view.setTag(path);
        mExecutor.execute(() -> {
            Bitmap bitmap = MangaThumbCache.load(mContext, cover, mTargetPx);
            if (bitmap == null) {
                bitmap = decodeScaled(cover, mTargetPx, mTargetPx);
                if (bitmap != null) {
                    MangaThumbCache.save(mContext, cover, mTargetPx, bitmap);
                }
            }
            final Bitmap result = bitmap;
            mMainHandler.post(() -> {
                if (result == null) {
                    return;
                }
                if (path.equals(view.getTag())) {
                    view.setImageBitmap(result);
                } else {
                    result.recycle();
                }
            });
        });
    }

    /** Decode an image scaled down so neither dimension exceeds the request. */
    @Nullable
    public static Bitmap decodeScaled(@NonNull File file, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            MangaImageDecoder.decodeBounds(file, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight);
            return MangaImageDecoder.decodeSampled(file,
                    computeInSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight),
                    Bitmap.Config.RGB_565);
        } catch (Throwable t) {
            return null;
        }
    }

    public static int computeInSampleSize(int width, int height, int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    // ---------------------------------------------------------------------
    // Natural (human) ordering: "page2" < "page10", case-insensitive.
    // Shared by the library (folder/image sorting) and the reader (pages).
    // ---------------------------------------------------------------------

    public static final Comparator<String> NATURAL_ORDER = MangaCoverAdapter::naturalCompare;

    public static int naturalCompare(@Nullable String a, @Nullable String b) {
        final String sa = a == null ? "" : a;
        final String sb = b == null ? "" : b;
        int ia = 0;
        int ib = 0;
        while (ia < sa.length() && ib < sb.length()) {
            char ca = sa.charAt(ia);
            char cb = sb.charAt(ib);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ja = ia;
                while (ja < sa.length() && Character.isDigit(sa.charAt(ja))) {
                    ja++;
                }
                int jb = ib;
                while (jb < sb.length() && Character.isDigit(sb.charAt(jb))) {
                    jb++;
                }
                int cmp = compareNumeric(sa.substring(ia, ja), sb.substring(ib, jb));
                if (cmp != 0) {
                    return cmp;
                }
                ia = ja;
                ib = jb;
            } else {
                int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (cmp != 0) {
                    return cmp;
                }
                ia++;
                ib++;
            }
        }
        return Integer.compare(sa.length() - ia, sb.length() - ib);
    }

    private static int compareNumeric(@NonNull String a, @NonNull String b) {
        final String na = stripLeadingZeros(a);
        final String nb = stripLeadingZeros(b);
        if (na.length() != nb.length()) {
            return Integer.compare(na.length(), nb.length());
        }
        return na.compareTo(nb);
    }

    @NonNull
    private static String stripLeadingZeros(@NonNull String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }
}
