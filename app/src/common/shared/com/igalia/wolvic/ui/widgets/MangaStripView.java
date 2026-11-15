/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.widgets;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws a seamless vertical "long strip" for continuous reading mode.
 *
 * The host keeps five decoded pages (current, two before, two after) and slides
 * the window along them. We deliberately draw each page as its own bitmap
 * instead of stitching them into one giant bitmap: Android's RecordingCanvas
 * rejects a single bitmap above its size limit (~174 MB here), while drawing N
 * ordinary bitmaps into one canvas is perfectly fine.
 */
public class MangaStripView extends View {

    /** [index-2, index-1, index, index+1, index+2] */
    private final Bitmap[] mSegments = new Bitmap[5];
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Matrix mMatrix = new Matrix();
    private float mScale = 1.0f;
    private float mZoom = 1.0f;
    private float mOffset = 0.0f;   // distance scrolled down from the top of the current page

    public MangaStripView(Context context) {
        super(context);
    }

    public MangaStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setStrip(Bitmap[] segments, float scale, float zoom, float offset) {
        for (int i = 0; i < 5; i++) {
            mSegments[i] = segments != null && i < segments.length ? segments[i] : null;
        }
        mScale = scale > 0 ? scale : 1.0f;
        mZoom = zoom > 0 ? zoom : 1.0f;
        mOffset = offset;
        invalidate();
    }

    public void setOffset(float offset) {
        mOffset = offset;
        invalidate();
    }

    public void setZoom(float zoom) {
        mZoom = zoom > 0 ? zoom : 1.0f;
        invalidate();
    }

    public float getOffset() {
        return mOffset;
    }

    public float scaledHeight(int segment) {
        Bitmap b = segment >= 0 && segment < 5 ? mSegments[segment] : null;
        return b == null || b.isRecycled() ? 0f : b.getHeight() * mScale * mZoom;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float vw = getWidth();
        final float vh = getHeight();
        if (vw <= 0 || vh <= 0 || mSegments[2] == null) {
            return;
        }
        final float s = mScale * mZoom;

        // Top edge of every segment, anchored on the current page at -offset.
        final float[] tops = new float[5];
        tops[2] = -mOffset;
        for (int i = 1; i >= 0; i--) {
            tops[i] = tops[i + 1] - scaledHeight(i + 1);
        }
        for (int i = 3; i < 5; i++) {
            tops[i] = tops[i - 1] + scaledHeight(i - 1);
        }

        for (int i = 0; i < 5; i++) {
            Bitmap b = mSegments[i];
            if (b == null || b.isRecycled()) {
                continue;
            }
            float h = b.getHeight() * s;
            if (tops[i] >= vh || tops[i] + h <= 0) {
                continue;
            }
            float w = b.getWidth() * s;
            float left = (vw - w) / 2.0f;
            try {
                mMatrix.reset();
                mMatrix.setScale(s, s);
                mMatrix.postTranslate(left, tops[i]);
                canvas.drawBitmap(b, mMatrix, mPaint);
            } catch (Throwable ignored) {
                // A recycled / oversized page must never take down the reader.
            }
        }
    }
}
