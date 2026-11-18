/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.adapters;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
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
import com.igalia.wolvic.manga.MangaSourceRepository;

import java.util.ArrayList;
import java.util.List;

import eu.kanade.tachiyomi.source.Source;
import eu.kanade.tachiyomi.source.model.SManga;

/** Bookshelf-style cover grid for online extension sources. */
public class SourceCoverAdapter extends RecyclerView.Adapter<SourceCoverAdapter.CoverViewHolder> {

    public interface OnSourceMangaClickListener {
        void onMangaClick(@NonNull SManga manga);
    }

    private final Context mContext;
    private final Source mSource;
    private final OnSourceMangaClickListener mListener;
    private final List<SManga> mItems = new ArrayList<>();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> mCache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
            return value.getByteCount();
        }
    };

    public SourceCoverAdapter(@NonNull Context context, @NonNull Source source,
                              @NonNull OnSourceMangaClickListener listener) {
        mContext = context;
        mSource = source;
        mListener = listener;
    }

    public void update(@Nullable List<SManga> items) {
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
        final SManga manga = mItems.get(position);
        holder.title.setText(manga.getTitle());
        int targetPx = (int) (200 * MangaSettings.librarySizeFactor(
                MangaSettings.get(mContext).integer(MangaSettings.LIBRARY_SIZE))
                * mContext.getResources().getDisplayMetrics().density + 0.5f);
        ViewGroup.LayoutParams lp = holder.cover.getLayoutParams();
        if (lp != null && lp.height != targetPx) {
            lp.height = targetPx;
            holder.cover.setLayoutParams(lp);
        }
        final String url = manga.getThumbnail_url();
        holder.cover.setImageDrawable(null);
        holder.cover.setTag(url);
        Bitmap cached = url == null ? null : mCache.get(url);
        if (cached != null) {
            holder.cover.setImageBitmap(cached);
        } else if (url != null) {
            MangaSourceRepository.INSTANCE.loadCover(mSource, url, (bitmap, error) -> {
                if (bitmap == null) {
                    return;
                }
                mCache.put(url, bitmap);
                if (url.equals(holder.cover.getTag())) {
                    holder.cover.setImageBitmap(bitmap);
                }
            });
        }
        holder.itemView.setOnClickListener(v -> mListener.onMangaClick(manga));
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
}