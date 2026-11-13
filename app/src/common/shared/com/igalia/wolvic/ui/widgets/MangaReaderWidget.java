/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.widgets;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.igalia.wolvic.R;
import com.igalia.wolvic.ui.adapters.MangaCoverAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A basic paged image reader for one manga folder. Shows a single page image,
 * a page counter and Prev/Next/Back controls. Pages are decoded on a background
 * thread, scaled to the view, and the previous bitmap is recycled.
 */
public class MangaReaderWidget extends UIWidget {

    private static final String[] IMAGE_EXTENSIONS = {"jpg", "jpeg", "png", "webp", "gif"};
    private static final int FALLBACK_TARGET_PX = 1200;

    private ImageView mPageImage;
    private TextView mPageCounter;

    private final List<File> mPages = new ArrayList<>();
    private int mIndex = 0;
    private Bitmap mCurrent;
    private Runnable mOnBack;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public MangaReaderWidget(Context aContext) {
        super(aContext);
        initialize();
    }

    private void initialize() {
        LayoutInflater.from(getContext()).inflate(R.layout.manga_reader, this, true);

        mPageImage = findViewById(R.id.pageImage);
        mPageCounter = findViewById(R.id.pageCounter);
        Button prevButton = findViewById(R.id.prevButton);
        Button nextButton = findViewById(R.id.nextButton);
        Button backButton = findViewById(R.id.backButton);

        if (prevButton != null) {
            prevButton.setOnClickListener(v -> showPage(mIndex - 1));
        }
        if (nextButton != null) {
            nextButton.setOnClickListener(v -> showPage(mIndex + 1));
        }
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                hide(REMOVE_WIDGET);
                if (mOnBack != null) {
                    mOnBack.run();
                }
            });
        }
    }

    @Override
    protected void initializeWidgetPlacement(WidgetPlacement aPlacement) {
        aPlacement.width = 1200;
        aPlacement.height = 800;
        aPlacement.worldWidth = 1.6f;
        aPlacement.anchorX = 0.5f;
        aPlacement.anchorY = 0.5f;
        aPlacement.translationZ = -0.2f;
        aPlacement.cylinder = false;
        aPlacement.visible = false;
    }

    /** Called by the bookshelf when the reader is dismissed via Back. */
    public void setOnBackListener(Runnable onBack) {
        mOnBack = onBack;
    }

    /** Load the folder's image files (natural sort) and show the first page. */
    public void setFolder(File folder) {
        mPages.clear();
        mIndex = 0;
        if (folder != null && folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && hasImageExtension(f.getName())) {
                        mPages.add(f);
                    }
                }
                mPages.sort((f1, f2) ->
                        MangaCoverAdapter.naturalCompare(f1.getName(), f2.getName()));
            }
        }
        showPage(0);
    }

    private void showPage(int index) {
        if (mPages.isEmpty()) {
            if (mPageCounter != null) {
                mPageCounter.setText("0 / 0");
            }
            if (mPageImage != null) {
                mPageImage.setImageDrawable(null);
            }
            return;
        }

        mIndex = Math.max(0, Math.min(index, mPages.size() - 1));
        if (mPageCounter != null) {
            mPageCounter.setText((mIndex + 1) + " / " + mPages.size());
        }

        final File page = mPages.get(mIndex);
        final int targetWidth = (mPageImage != null && mPageImage.getWidth() > 0)
                ? mPageImage.getWidth() : FALLBACK_TARGET_PX;
        final int targetHeight = (mPageImage != null && mPageImage.getHeight() > 0)
                ? mPageImage.getHeight() : FALLBACK_TARGET_PX;
        final int requestIndex = mIndex;

        mExecutor.execute(() -> {
            final Bitmap bitmap = decodePage(page, targetWidth, targetHeight);
            mMainHandler.post(() -> {
                // Ignore results for a page the user already navigated away from.
                if (requestIndex != mIndex) {
                    if (bitmap != null) {
                        bitmap.recycle();
                    }
                    return;
                }
                Bitmap old = mCurrent;
                mCurrent = bitmap;
                if (mPageImage != null) {
                    mPageImage.setImageBitmap(bitmap);
                }
                if (old != null && old != bitmap && !old.isRecycled()) {
                    old.recycle();
                }
            });
        });
    }

    @Override
    public void hide(@HideFlags int aHideFlags) {
        super.hide(aHideFlags);
        if (aHideFlags == REMOVE_WIDGET) {
            mExecutor.shutdownNow();
            if (mCurrent != null && !mCurrent.isRecycled()) {
                mCurrent.recycle();
                mCurrent = null;
            }
        }
    }

    private static Bitmap decodePage(File file, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = MangaCoverAdapter.computeInSampleSize(
                    bounds.outWidth, bounds.outHeight, reqWidth, reqHeight);
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasImageExtension(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String ext : IMAGE_EXTENSIONS) {
            if (lower.endsWith("." + ext)) {
                return true;
            }
        }
        return false;
    }
}