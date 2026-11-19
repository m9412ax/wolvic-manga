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
import android.graphics.Rect;
import android.graphics.RectF;
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
    // FILTER_BITMAP_FLAG keeps scaled tiles smooth; DITHER_FLAG was dropped
    // because per-frame dithering of large bitmaps cost measurable GPU time
    // during continuous scrolling with no visible benefit on line art.
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix mMatrix = new Matrix();
    private float mScale = 1.0f;
    private float mZoom = 1.0f;
    private float mOffset = 0.0f;   // distance scrolled down from the top of the current page
    /** Single oversized page split into full-resolution vertical tiles. */
    private Bitmap[] mTiles;
    private boolean mTileMode = false;
    private final Matrix mTileMatrix = new Matrix();

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

    /** Draws one page that is too large for a single Canvas bitmap as a
     *  vertical stack of independent, FULL-resolution tiles. Stitching them
     *  into one Bitmap would hit RecordingCanvas' size limit again; drawing
     *  N ordinary bitmaps into one canvas is fine. */
    public void setTiles(Bitmap[] tiles, Matrix matrix) {
        mTiles = tiles;
        mTileMode = tiles != null && tiles.length > 0;
        if (matrix != null) {
            mTileMatrix.set(matrix);
        }
        invalidate();
    }

    public void setTileMatrix(Matrix matrix) {
        if (matrix != null) {
            mTileMatrix.set(matrix);
            invalidate();
        }
    }

    public void clearTiles() {
        mTiles = null;
        mTileMode = false;
        invalidate();
    }

    public void setOffset(float offset) {
        mOffset = offset;
        postInvalidateOnAnimation();
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
        if (vw <= 0 || vh <= 0) {
            return;
        }
        if (mTileMode && mTiles != null) {
            // Only rasterise the strips that intersect the viewport. Without
            // this, zooming into a 30 000 px page re-drew every full-resolution
            // strip on every frame (very slow, and the repeated surface updates
            // showed up as flicker).
            float[] values = new float[9];
            mTileMatrix.getValues(values);
            float sy = values[Matrix.MSCALE_Y] != 0f ? values[Matrix.MSCALE_Y] : 1f;
            float ty = values[Matrix.MTRANS_Y];
            float visibleTop = -ty / sy;
            float visibleBottom = (vh - ty) / sy;
            canvas.save();
            canvas.concat(mTileMatrix);
            float top = 0.0f;
            for (Bitmap tile : mTiles) {
                if (tile == null || tile.isRecycled()) {
                    break;
                }
                float h = tile.getHeight();
                float sliceTop = Math.max(top, visibleTop);
                float sliceBottom = Math.min(top + h, visibleBottom);
                if (sliceBottom > sliceTop) {
                    try {
                        // Draw only the intersecting slice. Passing a src rect
                        // keeps the GPU from rasterising the whole full-res tile
                        // on every scroll frame.
                        Rect src = new Rect(0, (int) (sliceTop - top), tile.getWidth(),
                                Math.min(tile.getHeight(), (int) (sliceBottom - top)));
                        if (src.bottom > src.top) {
                            RectF dst = new RectF(0f, sliceTop, tile.getWidth(), sliceBottom);
                            canvas.drawBitmap(tile, src, dst, mPaint);
                        }
                    } catch (Throwable ignored) {
                        // An oversized / recycled tile must never kill the reader.
                    }
                }
                top += h;
            }
            canvas.restore();
            return;
        }
        if (mSegments[2] == null) {
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
            float visibleTop = Math.max(0f, -tops[i]);
            float visibleBottom = Math.min(h, vh - tops[i]);
            if (visibleBottom <= visibleTop) {
                continue;
            }
            try {
                Rect src = new Rect(0,
                        (int) Math.max(0f, visibleTop / s),
                        b.getWidth(),
                        (int) Math.min(b.getHeight(), visibleBottom / s));
                if (src.bottom > src.top) {
                    RectF dst = new RectF(left, tops[i] + visibleTop,
                            left + w, tops[i] + visibleBottom);
                    canvas.drawBitmap(b, src, dst, mPaint);
                }
            } catch (Throwable ignored) {
                // A recycled / oversized page must never take down the reader.
            }
        }
    }
}
