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
    /** When true, EVERY segment is scaled by its OWN width to fill the canvas
     *  width, instead of sharing one scale anchored on the current page. This
     *  makes a small page fill the canvas the moment it appears (no "small at
     *  the top, suddenly big in the middle"). */
    private boolean mFillWidth = false;
    private float mPanX = 0f;
    private float mPanY = 0f;
    /** Single oversized page split into full-resolution vertical tiles. */
    private Bitmap[] mTiles;
    private boolean mTileMode = false;
    private final Matrix mTileMatrix = new Matrix();
    // Fast continuous scrolling: bilinear-filtering huge bitmaps every frame is
    // expensive. Turn filtering off while the offset jumps a lot per frame and
    // restore it shortly after scrolling settles, so normal reading stays crisp.
    private float mLastOffset = 0f;
    private boolean mFilterOff = false;
    private final Runnable mRestoreFilter = () -> {
        if (mFilterOff) {
            mFilterOff = false;
            mPaint.setFilterBitmap(true);
            postInvalidateOnAnimation();
        }
    };

    public MangaStripView(Context context) {
        super(context);
    }

    public MangaStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setStrip(Bitmap[] segments, float scale, float zoom, float offset) {
        boolean changed = mScale != (scale > 0 ? scale : 1.0f)
                || mZoom != (zoom > 0 ? zoom : 1.0f);
        for (int i = 0; i < 5; i++) {
            Bitmap b = segments != null && i < segments.length ? segments[i] : null;
            if (mSegments[i] != b) {
                changed = true;
                mSegments[i] = b;
            }
        }
        mScale = scale > 0 ? scale : 1.0f;
        mZoom = zoom > 0 ? zoom : 1.0f;
        mOffset = offset;
        if (mFillWidth) {
            mFillWidth = false;
            changed = true;
            mPanX = 0f;
            mPanY = 0f;
        }
        // Decode completions call setStrip several times with identical content;
        // skip redundant invalidates that compete with the scroll frames.
        if (changed) {
            postInvalidateOnAnimation();
        }
    }

    /** Fill-width mode: each page fills the canvas width using its OWN width,
     *  so a small page is full-width from the moment it appears. Also carries
     *  a pan offset for pointer-anchored / trigger-drag zoom in continuous mode. */
    public void setStripFill(Bitmap[] segments, float zoom, float offset,
                             float panX, float panY) {
        boolean changed = !mFillWidth || mZoom != (zoom > 0 ? zoom : 1.0f)
                || mPanX != panX || mPanY != panY;
        mFillWidth = true;
        for (int i = 0; i < 5; i++) {
            Bitmap b = segments != null && i < segments.length ? segments[i] : null;
            if (mSegments[i] != b) {
                changed = true;
                mSegments[i] = b;
            }
        }
        mZoom = zoom > 0 ? zoom : 1.0f;
        mOffset = offset;
        mPanX = panX;
        mPanY = panY;
        if (changed) {
            postInvalidateOnAnimation();
        }
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
        float step = Math.abs(offset - mLastOffset);
        float height = getHeight() > 0 ? getHeight() : 1000f;
        // A large per-frame jump means a fast flick; drop bilinear filtering to
        // keep the frame budget. Restore it 120 ms after the motion settles.
        // NEVER in tile mode: a giant 1:1 page is drawn heavily minified, and
        // without filtering its text turns into crawling pixels.
        if (!mTileMode && step > height * 0.12f && !mFilterOff) {
            mFilterOff = true;
            mPaint.setFilterBitmap(false);
        }
        removeCallbacks(mRestoreFilter);
        postDelayed(mRestoreFilter, 120L);
        mLastOffset = offset;
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
        if (b == null || b.isRecycled()) return 0f;
        if (mFillWidth) {
            float vw = getWidth() > 0 ? getWidth() : 1000f;
            float sw = vw / (float) b.getWidth();
            return b.getHeight() * sw * mZoom;
        }
        return b.getHeight() * mScale * mZoom;
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
                        // Draw only the intersecting slice. Integer SOURCE rows
                        // with the dest computed from those same rows: the old
                        // fractional top mapped a truncated source rect onto a
                        // fractional dest, so each scroll frame re-sampled a
                        // slightly different pixel band along the tile seams
                        // (visible as a 1 px crawl / shimmer over the text).
                        int srcTop = Math.max(0, (int) Math.floor(sliceTop - top));
                        int srcBottom = Math.min(tile.getHeight(),
                                (int) Math.ceil(sliceBottom - top));
                        if (srcBottom > srcTop) {
                            Rect src = new Rect(0, srcTop, tile.getWidth(), srcBottom);
                            RectF dst = new RectF(0f, top + srcTop,
                                    tile.getWidth(), top + srcBottom);
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
            // In fill-width mode each page uses its OWN width scale, so every
            // page is full-canvas-width from the first pixel (small pages no
            // longer appear narrow and jump big near the middle). Otherwise
            // all pages share the current-page scale.
            final float si = mFillWidth ? (vw / (float) b.getWidth()) * mZoom : s;
            float h = b.getHeight() * si;
            if (tops[i] >= vh || tops[i] + h <= 0) {
                continue;
            }
            float w = b.getWidth() * si;
            float left = (vw - w) / 2.0f + (mFillWidth ? mPanX : 0f);
            float topShift = mFillWidth ? mPanY : 0f;
            float visibleTop = Math.max(0f, -tops[i] - topShift);
            float visibleBottom = Math.min(h, vh - tops[i] - topShift);
            if (visibleBottom <= visibleTop) {
                continue;
            }
            try {
                // Same rule as the tile path: an INTEGER source window whose
                // dest is derived from it. Cropping with a fractional offset
                // while drawing at a fractional position makes the whole page
                // shift by sub-pixels on every scroll frame, which is the
                // "文字一直在抖" effect when reading a long strip.
                int srcTop = Math.max(0, (int) Math.floor(visibleTop / si));
                int srcBottom = Math.min(b.getHeight(),
                        (int) Math.ceil(visibleBottom / si));
                if (srcBottom > srcTop) {
                    float dstTop = tops[i] + topShift + srcTop * si;
                    float dstBottom = tops[i] + topShift + srcBottom * si;
                    Rect src = new Rect(0, srcTop, b.getWidth(), srcBottom);
                    RectF dst = new RectF(left, dstTop, left + w, dstBottom);
                    canvas.drawBitmap(b, src, dst, mPaint);
                }
            } catch (Throwable ignored) {
                // A recycled / oversized page must never take down the reader.
            }
        }
    }
}
