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

import com.igalia.wolvic.manga.MangaImageDecoder;

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
    /** Downscaled copies of the pages on screen, resampled to roughly the
     *  number of pixels they actually cover.
     *
     *  Why: a page is decoded at native resolution, and continuous mode then
     *  shrinks it to the canvas width times the zoom (often 0.2 - 0.5). Handing
     *  that to the GPU means a plain bilinear tap over a 2-5x oversized
     *  texture: most source pixels are skipped, and every scroll frame changes
     *  which ones -> moire, crawling text, "抖动". Aves (Glide underneath)
     *  solves the same problem by decoding at display resolution instead of
     *  minifying at draw time. We cannot re-decode here, but one CPU resample
     *  with our step-halving filter gives the GPU a near 1:1 texture, so the
     *  sampling problem disappears (and the frame gets cheaper too). */
    private final Bitmap[] mDisp = new Bitmap[5];
    private final float[] mDispK = new float[5];      // disp.width / source.width
    private final int[] mDispTarget = new int[5];     // requested disp width
    private final java.util.concurrent.ExecutorService mScaler =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "manga-strip-scale");
                t.setDaemon(true);
                return t;
            });
    private final android.os.Handler mMain =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** Below this on-screen scale the source is prefiltered instead of being
     *  minified by the GPU on every frame. */
    private static final float PRESCALE_BELOW = 0.92f;
    /** Layer-texture pixels per view pixel (Wolvic oversamples panels; the
     *  reader can lower it through READER_RENDER_SCALE). Multiplying the
     *  on-screen scale by it gives the real resolution of the panel texture. */
    private float mRenderScale = 1.0f;

    public void setRenderScale(float scale) {
        mRenderScale = (scale > 0.05f && scale < 8f) ? scale : 1.0f;
    }

    public MangaStripView(Context context) {
        super(context);
    }

    public MangaStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** Long-strip pages are drawn far smaller than their pixel size, and the
     *  GPU then reads one texel per screen pixel: thin strokes sparkle and the
     *  text looks like it is crawling. Mipmaps fix that (trilinear sampling);
     *  the extra ~33% of texture memory only applies to pages on screen. */
    private static Bitmap ensureMip(Bitmap b) {
        if (b != null && !b.isRecycled() && !b.hasMipMap()) {
            try {
                b.setHasMipMap(true);
            } catch (Throwable ignored) {
                // Nothing to do; filtering still works, just with aliasing.
            }
        }
        return b;
    }

    private void dropDisp(int slot) {
        mDisp[slot] = null;
        mDispK[slot] = 1f;
        mDispTarget[slot] = 0;
    }

    /** Returns the bitmap to draw for [slot]: a prefiltered copy when the page
     *  is shown much smaller than its pixel size, otherwise the source itself.
     *  The resample runs off the UI thread; until it lands the source keeps
     *  drawing, so nothing flickers or jumps. */
    private Bitmap dispFor(int slot, Bitmap src, float si) {
        if (src == null || src.isRecycled()) {
            return src;
        }
        final int srcW = src.getWidth();
        final int srcH = src.getHeight();
        // The texture the compositor samples is far larger than the pixels the
        // panel covers, so only si * mRenderScale is real resolution.
        si = si * mRenderScale;
        if (si >= PRESCALE_BELOW || si <= 0.02f || srcW < 128) {
            if (mDisp[slot] != null) {
                dropDisp(slot);
            }
            return src;
        }
        // Quantise UP to 1/16 so a pinch reuses one resample, and so the drawn
        // copy is never upscaled (that would just look soft).
        // Nearest 1/32, never above the target: ceiling left copies up to 6%
        // wider than the texture the compositor samples, which it then had to
        // shrink again - that second pass is visible as softness at 1:1.
        float q = (float) Math.round(si * 32f) / 32f;
        if (q > si) {
            q = si;
        }
        if (q >= 1f) {
            return src;
        }
        if (q < 0.0625f) {
            q = 0.0625f;
        }
        Bitmap cached = mDisp[slot];
        if (cached != null && !cached.isRecycled()
                && Math.abs(mDispK[slot] - q) < 0.03f) {
            return cached;
        }
        final int target = Math.max(48, Math.round(srcW * q));
        if (mDispTarget[slot] != target) {
            mDispTarget[slot] = target;
            final int th = Math.max(1, Math.round(srcH * q));
            final Bitmap source = src;
            mScaler.execute(() -> {
                Bitmap out = null;
                try {
                    out = MangaImageDecoder.scaleFiltered(source, target, th);
                } catch (Throwable ignored) {
                    // OOM / recycled source: keep drawing the original.
                }
                if (out == null || out == source || out.isRecycled()) {
                    return;
                }
                final Bitmap result = out;
                mMain.post(() -> {
                    // The slot may have been re-seeded (page turn) meanwhile.
                    if (mSegments[slot] != source
                            || result.isRecycled()
                            || mDispTarget[slot] != target) {
                        return;
                    }
                    mDisp[slot] = result;
                    mDispK[slot] = result.getWidth() / (float) source.getWidth();
                    postInvalidateOnAnimation();
                });
            });
        }
        return cached != null && !cached.isRecycled() ? cached : src;
    }

    public void setStrip(Bitmap[] segments, float scale, float zoom, float offset) {
        boolean changed = mScale != (scale > 0 ? scale : 1.0f)
                || mZoom != (zoom > 0 ? zoom : 1.0f);
        for (int i = 0; i < 5; i++) {
            Bitmap b = segments != null && i < segments.length
                    ? ensureMip(segments[i]) : null;
            if (mSegments[i] != b) {
                changed = true;
                mSegments[i] = b;
                dropDisp(i);
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
            Bitmap b = segments != null && i < segments.length
                    ? ensureMip(segments[i]) : null;
            if (mSegments[i] != b) {
                changed = true;
                mSegments[i] = b;
                dropDisp(i);
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
        if (tiles != null) {
            for (int i = 0; i < tiles.length; i++) {
                tiles[i] = ensureMip(tiles[i]);
            }
        }
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
        // Filtering stays ON at all times: with the prefiltered copies above a
        // scaled blit is already cheap, and the old "drop the filter while
        // flicking" trick made minified text crawl exactly when it was moving.
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
            Bitmap full = mSegments[i];
            if (full == null || full.isRecycled()) {
                continue;
            }
            // In fill-width mode each page uses its OWN width scale, so every
            // page is full-canvas-width from the first pixel (small pages no
            // longer appear narrow and jump big near the middle). Otherwise
            // all pages share the current-page scale.
            final float siFull = mFillWidth
                    ? (vw / (float) full.getWidth()) * mZoom : s;
            float h = full.getHeight() * siFull;
            if (tops[i] >= vh || tops[i] + h <= 0) {
                continue;
            }
            // Near 1:1 we draw the page as decoded; when it is shown much
            // smaller we draw a prefiltered copy of the same picture.
            Bitmap b = dispFor(i, full, siFull);
            float k = b.getWidth() / (float) full.getWidth();
            if (k <= 0.001f) {
                k = 1f;
            }
            final float si = siFull / k;             // view px per drawn px
            float w = b.getWidth() * si;
            float leftF = (vw - w) / 2.0f + (mFillWidth ? mPanX : 0f);
            float topShift = mFillWidth ? mPanY : 0f;
            float visibleTop = Math.max(0f, -tops[i] - topShift);
            float visibleBottom = Math.min(h, vh - tops[i] - topShift);
            if (visibleBottom <= visibleTop) {
                continue;
            }
            try {
                // Integer source rows AND an integer destination rect. A
                // fractional destination makes the sampler blend a slightly
                // different pair of pixels on every scroll frame - that
                // sub-pixel phase drift is the "1:1 时边缘轻微晃动, 缩小后
                // 整个画面抖" effect. Rounding both ends pins the sampling
                // grid to the device pixels, so a static frame stops moving.
                int srcTop = Math.max(0, (int) Math.floor(visibleTop / si));
                int srcBottom = Math.min(b.getHeight(),
                        (int) Math.ceil(visibleBottom / si));
                if (srcBottom <= srcTop) {
                    continue;
                }
                int left = Math.round(leftF);
                int right = Math.round(leftF + w);
                int dstTop = Math.round(tops[i] + topShift + srcTop * si);
                int dstBottom = Math.round(tops[i] + topShift + srcBottom * si);
                if (right <= left || dstBottom <= dstTop) {
                    continue;
                }
                Rect src = new Rect(0, srcTop, b.getWidth(), srcBottom);
                Rect dst = new Rect(left, dstTop, right, dstBottom);
                canvas.drawBitmap(b, src, dst, mPaint);
            } catch (Throwable ignored) {
                // A recycled / oversized page must never take down the reader.
            }
        }
    }
}
