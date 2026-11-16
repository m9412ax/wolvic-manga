/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.adapters;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.igalia.wolvic.R;
import com.igalia.wolvic.manga.MangaSettings;
import com.igalia.wolvic.manga.MangaImageDecoder;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Grid adapter for the manga bookshelf. Each item is a local manga folder.
 * Covers are decoded on a background thread and scaled down to avoid OOM.
 * Plain LayoutInflater + findViewById (no DataBinding).
 */
public class MangaCoverAdapter extends RecyclerView.Adapter<MangaCoverAdapter.CoverViewHolder> {

    /** Click callback. The folder is the manga root directory. */
    public interface OnMangaClickListener {
        void onMangaClick(@NonNull File folder);
    }

    /** Long-press callback, e.g. to reset reading progress for a folder. */
    public interface OnMangaLongClickListener {
        void onMangaLongClick(@NonNull File folder);
    }

    /** One manga entry: a folder plus its discovered cover / video flag. */
    public static class MangaItem {
        @NonNull public final File folder;
        @NonNull public final String title;
        @Nullable public final File cover;
        public final boolean hasVideo;

        public MangaItem(@NonNull File folder, @NonNull String title,
                         @Nullable File cover, boolean hasVideo) {
            this.folder = folder;
            this.title = title;
            this.cover = cover;
            this.hasVideo = hasVideo;
        }
    }

    private static final int COVER_TARGET_PX = 320;

    private final Context mContext;
    private final List<MangaItem> mItems = new ArrayList<>();
    private final OnMangaClickListener mListener;
    private final OnMangaLongClickListener mLongListener;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    /** Cover decode budget from 设置 → 漫画 · 图源 → 封面解码尺寸. */
    private final int mTargetPx;
    /** Compact rows for the bookshelf list view. */
    private boolean mCompact = false;

    public void setCompact(boolean compact) {
        mCompact = compact;
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
        mItems.clear();
        if (items != null) {
            mItems.addAll(items);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CoverViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(mContext)
                .inflate(R.layout.manga_cover_item, parent, false);
        return new CoverViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CoverViewHolder holder, int position) {
        final MangaItem item = mItems.get(position);
        holder.title.setText(item.title);
        // Only touch layout params when the height actually changes. Setting
        // them unconditionally on every bind requests a layout from inside a
        // RecyclerView layout pass, which can loop forever and freeze the UI.
        int targetPx = (int) ((mCompact ? 96 : 200)
                * mContext.getResources().getDisplayMetrics().density + 0.5f);
        android.view.ViewGroup.LayoutParams lp = holder.cover.getLayoutParams();
        if (lp != null && lp.height != targetPx) {
            lp.height = targetPx;
            holder.cover.setLayoutParams(lp);
        }
        loadCover(holder.cover, item.cover);
        holder.itemView.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onMangaClick(item.folder);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (mLongListener != null) {
                mLongListener.onMangaLongClick(item.folder);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    static class CoverViewHolder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title;

        CoverViewHolder(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.coverImage);
            title = itemView.findViewById(R.id.coverTitle);
        }
    }

    private void loadCover(@NonNull ImageView view, @Nullable File cover) {
        view.setImageDrawable(null);
        if (cover == null) {
            view.setTag(null);
            return;
        }
        final String path = cover.getAbsolutePath();
        // Tag guards against recycled views receiving a stale bitmap.
        view.setTag(path);
        mExecutor.execute(() -> {
            final Bitmap bitmap = decodeScaled(cover, mTargetPx, mTargetPx);
            mMainHandler.post(() -> {
                if (bitmap == null) {
                    return;
                }
                if (path.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                } else {
                    bitmap.recycle();
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