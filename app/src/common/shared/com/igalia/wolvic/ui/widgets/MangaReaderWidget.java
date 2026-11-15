/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.widgets;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.igalia.wolvic.R;
import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.browser.SettingsStore;
import com.igalia.wolvic.manga.MangaSettings;
import com.igalia.wolvic.ui.adapters.MangaCoverAdapter;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Independent floating reader window. It deliberately has no control bar: all
 * persistent controls are the original Wolvic tray buttons. Every manga setting
 * from the native SettingsWidget is consumed here (direction, scale, brightness,
 * crop, zoom, tap zones, volume keys, auto page advance, preload, transitions,
 * progress and video policy).
 */
public class MangaReaderWidget extends UIWidget implements TextureView.SurfaceTextureListener {

    private static final String TAG = "MangaReader";
    /** Android's RecordingCanvas refuses to draw a single Bitmap above this
     *  size (observed crash at ~166 MB). This is a *draw* limit, not a decode
     *  limit: pages beyond it are decoded as full-resolution tiles instead of
     *  being downsampled, so no detail is ever thrown away. */
    private static final long SINGLE_BITMAP_LIMIT = 150L * 1024 * 1024;
    private static final long TILE_BYTES_LIMIT = 96L * 1024 * 1024;

    private MangaSettings mSettings;
    private FrameLayout mRootView;
    private FrameLayout mStage;
    private LinearLayout mGotoDialog;
    private ImageView mPageImage;
    private TextureView mVideoTexture;
    private TextView mPageCounter;
    private TextView mHint;
    private View mBrightnessOverlay;
    private EditText mGotoInput;

    private final List<File> mPages = new ArrayList<>();
    private File mFolder;
    private int mIndex;
    private int mLoadGeneration;
    private Bitmap mCurrent;
    private boolean mDestroyed;
    private boolean mVideoCompleted;

    private MediaPlayer mMediaPlayer;
    private Surface mVideoSurface;

    private final Matrix mMatrix = new Matrix();
    private float mZoom = 1.0f;
    private float mScrollY = 0.0f;
    private GestureDetector mGestureDetector;
    private LruCache<String, Bitmap> mCache;

    // Continuous mode: five live pages [index-2 .. index+2] held as strong
    // references (an LruCache would evict them mid-scroll and break the seam).
    private MangaStripView mStripView;
    private View mReaderDragBar;
    private final Bitmap[] mStripSegments = new Bitmap[5];
    private float mStripOffset = 0.0f;
    private float mStripScale = 1.0f;
    private float mStripLastZoom = 1.0f;
    private int mStripGeneration;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "manga-decode");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Runnable mAutoTick = this::onAutoTick;
    private final Runnable mHideHint = () -> {
        if (mHint != null) {
            mHint.setVisibility(GONE);
        }
    };

    // Joystick / controller support -------------------------------------------
    private boolean mDoublePage = false;
    private boolean mResizeModifier = false;   // a controller side button is held
    private float mAxisX = 0f;
    private float mAxisY = 0f;
    private float mScrollAccumX = 0f;
    private float mScrollAccumY = 0f;
    private final Runnable mScrollDecay = () -> {
        mScrollAccumX = 0f;
        mScrollAccumY = 0f;
    };
    private boolean mJoyActive = false;
    private boolean mJoyPageCooldown = false;
    private float mUserScale = 1.0f;           // user window-size multiplier
    /** Paging from the thumbstick is suspended while a zoom gesture is in
     *  progress (and briefly afterwards), so a resize never flips pages. */
    private long mZoomGestureUntil = 0L;
    /** True when the current page is drawn as a stack of decoded tiles. */
    private boolean mTiledPage = false;
    /** Native dimensions of the current page, even when it is tiled. */
    private int mDisplayWidth = 0;
    private int mDisplayHeight = 0;
    private final Runnable mJoyTick = this::joyTick;

    public MangaReaderWidget(Context aContext) {
        super(aContext);
        initialize();
    }

    private void initialize() {
        mSettings = MangaSettings.get(getContext());
        // Canvas size is persistent across pages and restarts; image zoom is not.
        mUserScale = clamp(mSettings.getFloat(MangaSettings.READER_CANVAS_SCALE, 1.0f),
                0.4f, 3.0f);
        mDoublePage = mSettings.bool(MangaSettings.READER_DOUBLE_PAGE);
        setIsHardwareAccelerationEnabled(mSettings.bool(MangaSettings.ADV_HW_ACCEL));
        LayoutInflater.from(getContext()).inflate(R.layout.manga_reader, this, true);

        mRootView = findViewById(R.id.readerRoot);
        mStage = findViewById(R.id.pageStage);
        mGotoDialog = findViewById(R.id.gotoDialog);
        mPageImage = findViewById(R.id.pageImage);
        mVideoTexture = findViewById(R.id.videoTexture);
        mPageCounter = findViewById(R.id.pageCounter);
        mHint = findViewById(R.id.readerHint);
        mBrightnessOverlay = findViewById(R.id.brightnessOverlay);
        mGotoInput = findViewById(R.id.gotoInput);
        mStripView = findViewById(R.id.stripView);
        mReaderDragBar = findViewById(R.id.readerDragBar);

        mPageImage.setScaleType(ImageView.ScaleType.MATRIX);
        mPageImage.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or_, ob) -> applyImageTransform());
        if (mStripView != null) {
            mStripView.addOnLayoutChangeListener(
                    (v, l, t, r, b, ol, ot, or_, ob) -> updateStripTransform());
        }
        setupReaderDrag();
        mVideoTexture.setSurfaceTextureListener(this);

        int cacheKb = Math.max(16, mSettings.integer(MangaSettings.CACHE_MB)) * 1024;
        mCache = new LruCache<String, Bitmap>(cacheKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };

        mGestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                handleTap(e.getX());
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (mSettings.bool(MangaSettings.READER_DOUBLE_TAP_ZOOM)) {
                    mZoom = mZoom > 1.01f ? 1.0f : 2.0f;
                    applyImageTransform();
                    showHint(mZoom > 1.01f ? "已放大 2×" : "已还原缩放");
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (isContinuous() && mZoom <= 1.01f) {
                    scrollContinuous(-distanceY);
                    return true;
                }
                if (mSettings.integer(MangaSettings.READER_DIRECTION) == 2 && mZoom <= 1.01f) {
                    mScrollY -= distanceY;
                    applyImageTransform();
                    return true;
                }
                return false;
            }
        });
        mStage.setOnTouchListener((view, event) -> mGestureDetector.onTouchEvent(event));
        mStage.setOnGenericMotionListener((view, event) -> onJoyMotion(event));

        setFocusableInTouchMode(true);
        setOnKeyListener((view, keyCode, event) -> {
            // Holding a controller side button (grip / shoulder / thumbstick press)
            // switches the joystick into window-resize mode.
            if (isResizeKey(keyCode)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    mResizeModifier = true;
                    startJoyTick();
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    mResizeModifier = false;
                    stopJoyTick();
                }
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            // DPAD fallback for paging (works even if the joystick axis isn't forwarded).
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                pageDelta(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                pageDelta(1);
                return true;
            }
            if (mSettings.bool(MangaSettings.READER_VOLUME_KEYS)) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    pageDelta(-1);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    pageDelta(1);
                    return true;
                }
            }
            return false;
        });

        Button confirm = findViewById(R.id.gotoConfirm);
        Button cancel = findViewById(R.id.gotoCancel);
        confirm.setOnClickListener(v -> confirmGoto());
        cancel.setOnClickListener(v -> mGotoDialog.setVisibility(GONE));

        applyDisplaySettings();
        // Restore the persisted canvas/window size once the widget is live.
        if (mUserScale != 1.0f) {
            post(this::applyUserScale);
        }
    }

    /** Legacy in-widget grab bar. The floating MangaDragBarWidget now owns the
     *  handle, so this stays as a no-op when the layout no longer defines it. */
    private void setupReaderDrag() {
        if (mReaderDragBar == null) {
            return;
        }
        mReaderDragBar.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (mWidgetManager != null) {
                        mWidgetManager.startWidgetMove(this,
                                WidgetManagerDelegate.WIDGET_MOVE_BEHAVIOUR_MANGA);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mWidgetManager != null) {
                        mWidgetManager.finishWidgetMove();
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    @Override
    protected void initializeWidgetPlacement(WidgetPlacement aPlacement) {
        Context context = getContext();
        int windowWidth = SettingsStore.getInstance(context).getWindowWidth();
        int windowHeight = SettingsStore.getInstance(context).getWindowHeight();
        aPlacement.width = (int) (windowWidth * 0.78f) + mBorderWidth * 2;
        aPlacement.height = (int) (windowHeight * 0.82f) + mBorderWidth * 2;
        aPlacement.worldWidth = WidgetPlacement.floatDimension(context, R.dimen.window_world_width)
                * (float) windowWidth / (float) SettingsStore.WINDOW_WIDTH_DEFAULT * 0.60f;
        // Anchor the reader by its BOTTOM so it always sits a fixed gap above the
        // control bar (tray_world_y), instead of being vertically centered (which
        // pushed the bottom of the tall image below the tray and got it covered).
        float readerWorldHeight = aPlacement.worldWidth
                * ((float) aPlacement.height / (float) aPlacement.width) * 360.0f;
        float trayY = WidgetPlacement.unitFromMeters(context, R.dimen.tray_world_y);
        aPlacement.translationY = (trayY + 60.0f) + readerWorldHeight * 0.5f;
        // A truly separate floating surface, closer to the viewer than the shelf.
        aPlacement.translationZ = WidgetPlacement.getWindowWorldZMeters(context) + 220.0f;
        aPlacement.anchorX = 0.5f;
        aPlacement.anchorY = 0.5f;
        aPlacement.parentHandle = -1;
        aPlacement.cylinder = false;
        aPlacement.visible = false;
        Log.i(TAG, "placement w=" + aPlacement.width + " y=" + aPlacement.translationY
                + " z=" + aPlacement.translationZ);
    }

    // ----------------------------------------------------------- content

    public void setFolder(File folder) {
        stopVideo(true);
        cancelAuto();
        mLoadGeneration++;
        mFolder = folder;
        mPages.clear();
        mIndex = 0;
        mZoom = 1.0f;
        mScrollY = 0.0f;
        mStripOffset = 0.0f;
        mStripGeneration++;
        clearStripSegments();

        List<File> videos = new ArrayList<>();
        List<File> images = new ArrayList<>();
        String[] imageExt = mSettings.imageExtensions();
        String[] videoExt = mSettings.videoExtensions();
        collectFiles(folder, mSettings.bool(MangaSettings.SOURCE_RECURSIVE) ? 2 : 0,
                imageExt, videoExt, images, videos);
        boolean natural = mSettings.bool(MangaSettings.SOURCE_NATURAL_SORT);
        java.util.Comparator<File> comparator = natural
                ? (a, b) -> MangaCoverAdapter.naturalCompare(a.getName(), b.getName())
                : (a, b) -> a.getName().compareToIgnoreCase(b.getName());
        videos.sort(comparator);
        images.sort(comparator);
        if (mSettings.bool(MangaSettings.SOURCE_VIDEO_FIRST)) {
            mPages.addAll(videos);
            mPages.addAll(images);
        } else {
            List<File> all = new ArrayList<>();
            all.addAll(videos);
            all.addAll(images);
            all.sort(comparator);
            mPages.addAll(all);
        }

        int saved = folder == null || !mSettings.bool(MangaSettings.PROGRESS_ENABLED)
                ? 0 : progressPrefs().getInt(pageProgressKey(), 0);
        Log.i(TAG, "setFolder pages=" + mPages.size() + " saved=" + saved);
        showPage(Math.max(0, Math.min(saved, Math.max(0, mPages.size() - 1))));
    }

    private static void collectFiles(File dir, int depth, String[] imageExt, String[] videoExt,
                                     List<File> images, List<File> videos) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            if (MangaSettings.hasExtension(file.getName(), videoExt)) {
                videos.add(file);
            } else if (MangaSettings.hasExtension(file.getName(), imageExt)) {
                images.add(file);
            }
        }
        if (depth > 0) {
            for (File file : files) {
                if (file != null && file.isDirectory()) {
                    collectFiles(file, depth - 1, imageExt, videoExt, images, videos);
                }
            }
        }
    }

    private void showPage(int index) {
        if (mDestroyed || mPages.isEmpty() || index < 0 || index >= mPages.size()) {
            if (mPages.isEmpty()) {
                mPageCounter.setText("0 / 0");
                showHint("该目录没有可阅读的图片或视频");
            }
            return;
        }
        stopVideo(true);
        cancelAuto();
        final int generation = ++mLoadGeneration;
        final int stripGeneration = ++mStripGeneration;
        mIndex = index;
        mVideoCompleted = false;
        mZoom = 1.0f;
        mScrollY = 0.0f;
        mStripOffset = 0.0f;
        mStripLastZoom = 1.0f;
        clearStripSegments();
        if (mSettings.bool(MangaSettings.PROGRESS_ENABLED)) {
            progressPrefs().edit().putInt(pageProgressKey(), mIndex).apply();
        }

        final File page = mPages.get(mIndex);
        final boolean continuous = isContinuous();
        mTiledPage = false;
        mDisplayWidth = 0;
        mDisplayHeight = 0;
        if (mStripView != null) {
            mStripView.clearTiles();
        }
        applyDisplaySettings();
        mPageCounter.setText((mIndex + 1) + " / " + mPages.size());
        mGotoDialog.setVisibility(GONE);

        if (MangaSettings.hasExtension(page.getName(), mSettings.videoExtensions())) {
            mPageImage.setVisibility(GONE);
            mVideoTexture.setVisibility(VISIBLE);
            setStripVisible(false);
            if (mSettings.bool(MangaSettings.READER_VIDEO_AUTOPLAY)) {
                playVideo(page);
            } else {
                showHint("点击画面播放视频");
            }
            return;
        }

        mVideoTexture.setVisibility(GONE);
        // An oversized page is shown as full-resolution tiles rather than one
        // downsampled bitmap, so zooming in never loses text detail.
        final boolean tiled = !continuous && needsTiling(page);
        mPageImage.setVisibility(continuous || tiled ? GONE : VISIBLE);
        setStripVisible(continuous || tiled);
        final String cacheKey = continuous ? stripKey(mIndex) : displayKey(mIndex);
        if (tiled) {
            // Deliberately not cached in the Bitmap LruCache: a set of tiles can
            // be several hundred MB and must not evict all the small pages.
            mCurrent = null;
            mPageImage.setImageDrawable(null);
            mExecutor.execute(() -> {
                final Bitmap[] tiles = decodePageTiles(page);
                // Some formats (e.g. certain WebP/GIF variants) have no region
                // decoder. Fall back to a single sampled bitmap rather than
                // leaving the reader blank.
                final Bitmap fallback = (tiles == null || tiles.length == 0)
                        ? decodePage(page) : null;
                mMainHandler.post(() -> {
                    if (mDestroyed || generation != mLoadGeneration) {
                        return;
                    }
                    if (tiles == null || tiles.length == 0) {
                        if (fallback != null) {
                            mTiledPage = false;
                            mStripView.clearTiles();
                            setStripVisible(false);
                            mPageImage.setVisibility(VISIBLE);
                            mCurrent = fallback;
                            mDisplayWidth = fallback.getWidth();
                            mDisplayHeight = fallback.getHeight();
                            mPageImage.setImageBitmap(fallback);
                            mPageImage.post(this::applyImageTransform);
                        } else {
                            showHint("无法解码：" + page.getName());
                        }
                        finishPageLoad(generation);
                        return;
                    }
                    int totalW = 0;
                    int totalH = 0;
                    for (Bitmap tile : tiles) {
                        totalW = Math.max(totalW, tile.getWidth());
                        totalH += tile.getHeight();
                    }
                    mDisplayWidth = totalW;
                    mDisplayHeight = totalH;
                    mTiledPage = true;
                    mStripView.setTiles(tiles, null);
                    applyImageTransform();
                    // The strip view may not have been measured yet when it
                    // first becomes visible; recompute once layout completes.
                    mStripView.post(this::applyImageTransform);
                    finishPageLoad(generation);
                });
            });
            return;
        }
        Bitmap cached = mCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            mCurrent = cached;
            mDisplayWidth = cached.getWidth();
            mDisplayHeight = cached.getHeight();
            if (continuous) {
                rebuildStrip(cached, stripGeneration);
            } else {
                mPageImage.setImageBitmap(cached);
                mPageImage.post(this::applyImageTransform);
            }
            finishPageLoad(generation);
            return;
        }
        if (!continuous) {
            mPageImage.setImageDrawable(null);
        }
        mExecutor.execute(() -> {
            final Bitmap bitmap = continuous ? decodePage(page) : decodeDisplay(mIndex);
            mMainHandler.post(() -> {
                if (mDestroyed || generation != mLoadGeneration) {
                    return;
                }
                mCurrent = bitmap;
                if (bitmap != null) {
                    mDisplayWidth = bitmap.getWidth();
                    mDisplayHeight = bitmap.getHeight();
                    mCache.put(cacheKey, bitmap);
                    if (continuous) {
                        rebuildStrip(bitmap, stripGeneration);
                    } else {
                        mPageImage.setImageBitmap(bitmap);
                        mPageImage.post(this::applyImageTransform);
                    }
                } else {
                    showHint("无法解码：" + page.getName());
                }
                finishPageLoad(generation);
            });
        });
    }

    private void finishPageLoad(int generation) {
        applyTransition();
        preloadNext();
        scheduleAuto();
        Log.i(TAG, "showPage " + (mIndex + 1) + "/" + mPages.size()
                + " gen=" + generation);
        diagnostic("detail zoom=" + mZoom + " scale=" + mSettings.integer(MangaSettings.READER_SCALE)
                + " direction=" + mSettings.integer(MangaSettings.READER_DIRECTION)
                + " brightness=" + mSettings.integer(MangaSettings.READER_BRIGHTNESS)
                + " preload=" + mSettings.integer(MangaSettings.READER_PRELOAD)
                + " decodeMax=" + mSettings.integer(MangaSettings.ADV_MAX_DECODE_PX)
                + " cacheKb=" + (mCache == null ? 0 : mCache.maxSize()));
    }

    /** Verbose reader diagnostics, gated by 设置 → 漫画 · 高级 → 输出诊断日志. */
    private void diagnostic(String message) {
        if (mSettings != null && mSettings.bool(MangaSettings.ADV_LOGGING)) {
            Log.d(TAG, message);
        }
    }

    private Bitmap decodePage(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            // No resolution cap. Every page decodes at 1:1 native resolution;
            // the old 4096-edge / 150 MB downsampling was exactly what made
            // zoomed-in comic text look blurry. Only the continuous-mode path
            // (which needs one Bitmap per page) falls back to a sample when a
            // single bitmap would exceed Canvas' draw limit. Single-page mode
            // uses decodePageTiles() for those pages and stays at full res.
            long bytes = (long) bounds.outWidth * bounds.outHeight * 4L;
            int sample = 1;
            if (bytes > SINGLE_BITMAP_LIMIT) {
                while (sample <= 32) {
                    long w = (bounds.outWidth + sample - 1L) / sample;
                    long h = (bounds.outHeight + sample - 1L) / sample;
                    if (w * h * 4L <= SINGLE_BITMAP_LIMIT) {
                        break;
                    }
                    sample *= 2;
                }
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bitmap != null && mSettings.bool(MangaSettings.READER_CROP_BORDERS)) {
                Bitmap cropped = cropBorders(bitmap);
                if (cropped != null && cropped != bitmap) {
                    return cropped;
                }
            }
            return bitmap;
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when a page cannot be drawn as a single Bitmap and must be tiled. */
    private boolean needsTiling(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            return bounds.outWidth > 0 && bounds.outHeight > 0
                    && (long) bounds.outWidth * bounds.outHeight * 4L > SINGLE_BITMAP_LIMIT;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Full-resolution decode of a very tall / high-res page. The page is cut
     *  into vertical strips that are each small enough for RecordingCanvas;
     *  MangaStripView draws them as one continuous image. No pixel is ever
     *  discarded, so zooming in keeps every bit of the original detail. */
    private Bitmap[] decodePageTiles(File file) {
        try {
            BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(
                    file.getAbsolutePath(), false);
            if (decoder == null) {
                return null;
            }
            final int w = decoder.getWidth();
            final int h = decoder.getHeight();
            if (w <= 0 || h <= 0) {
                decoder.recycle();
                return null;
            }
            final int rows = (int) Math.max(1L, TILE_BYTES_LIMIT / ((long) w * 4L));
            List<Bitmap> tiles = new ArrayList<>();
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            for (int top = 0; top < h; top += rows) {
                int bottom = Math.min(h, top + rows);
                Bitmap tile = decoder.decodeRegion(new Rect(0, top, w, bottom), opts);
                if (tile == null) {
                    break;
                }
                tiles.add(tile);
            }
            decoder.recycle();
            return tiles.isEmpty() ? null : tiles.toArray(new Bitmap[0]);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Trims uniform white/black margins; bounded to 8% of each edge. */
    private Bitmap cropBorders(Bitmap source) {
        try {
            int width = source.getWidth();
            int height = source.getHeight();
            int limitY = Math.max(1, height / 12);
            int limitX = Math.max(1, width / 12);
            int top = 0;
            int bottom = height;
            int left = 0;
            int right = width;
            for (int y = 0; y < limitY; y++) {
                if (!blankRow(source, y, 0, width)) {
                    top = y;
                    break;
                }
            }
            for (int y = height - 1; y >= height - limitY; y--) {
                if (!blankRow(source, y, 0, width)) {
                    bottom = y + 1;
                    break;
                }
            }
            for (int x = 0; x < limitX; x++) {
                if (!blankColumn(source, x, top, bottom)) {
                    left = x;
                    break;
                }
            }
            for (int x = width - 1; x >= width - limitX; x--) {
                if (!blankColumn(source, x, top, bottom)) {
                    right = x + 1;
                    break;
                }
            }
            if (right - left < width / 2 || bottom - top < height / 2) {
                return source;
            }
            if (top == 0 && left == 0 && bottom == height && right == width) {
                return source;
            }
            return Bitmap.createBitmap(source, left, top, right - left, bottom - top);
        } catch (Throwable t) {
            return source;
        }
    }

    private boolean blankRow(Bitmap bitmap, int y, int from, int to) {
        int step = Math.max(1, (to - from) / 32);
        for (int x = from; x < to; x += step) {
            if (!blankPixel(bitmap.getPixel(x, y))) {
                return false;
            }
        }
        return true;
    }

    private boolean blankColumn(Bitmap bitmap, int x, int from, int to) {
        int step = Math.max(1, (to - from) / 32);
        for (int y = from; y < to; y += step) {
            if (!blankPixel(bitmap.getPixel(x, y))) {
                return false;
            }
        }
        return true;
    }

    private static boolean blankPixel(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (r > 238 && g > 238 && b > 238) || (r < 18 && g < 18 && b < 18);
    }

    private void preloadNext() {
        if (isContinuous()) {
            return; // continuous mode keeps its own +/-2 pages alive
        }
        int count = Math.max(0, Math.min(4, mSettings.integer(MangaSettings.READER_PRELOAD)));
        for (int i = 1; i <= count; i++) {
            final int target = mIndex + i;
            if (target >= mPages.size()) {
                break;
            }
            final File file = mPages.get(target);
            final String key = displayKey(target);
            if (mCache.get(key) != null
                    || MangaSettings.hasExtension(file.getName(), mSettings.videoExtensions())) {
                continue;
            }
            mExecutor.execute(() -> {
                Bitmap bitmap = decodeDisplay(target);
                if (bitmap != null) {
                    mMainHandler.post(() -> {
                        if (!mDestroyed && mCache.get(key) == null) {
                            mCache.put(key, bitmap);
                        }
                    });
                }
            });
        }
    }

    // -------------------------------------------------- continuous long strip

    private boolean isContinuous() {
        return mStripView != null && mSettings != null
                && mSettings.integer(MangaSettings.READER_DIRECTION) == 2;
    }

    private String stripKey(int index) {
        return "strip:" + (mFolder == null ? "" : mFolder.getAbsolutePath()) + ":" + index;
    }

    private void setStripVisible(boolean visible) {
        if (mStripView == null) {
            return;
        }
        mStripView.setVisibility(visible ? VISIBLE : GONE);
        if (!visible) {
            mStripView.setStrip(new Bitmap[5], 1.0f, 1.0f, 0.0f);
        }
    }

    private void clearStripSegments() {
        for (int i = 0; i < 5; i++) {
            mStripSegments[i] = null;
        }
    }

    /** Seeds the five-page window around the current page and decodes the
     *  missing neighbours in the background. Both directions are always read,
     *  so scrolling up from the very top of a page lands on the previous
     *  page's tail with no blank frame. */
    private void rebuildStrip(Bitmap current, int generation) {
        if (!isContinuous()) {
            return;
        }
        clearStripSegments();
        mStripSegments[2] = current;
        mStripOffset = 0.0f;
        mStripScale = 1.0f;
        mStripLastZoom = mZoom;
        updateStripTransform();
        // The strip may not have been laid out yet when the first page decodes.
        mStripView.post(this::updateStripTransform);
        loadStripNeighbours(generation);
    }

    private void loadStripNeighbours(int generation) {
        for (int slot = 0; slot < 5; slot++) {
            if (slot == 2 || mStripSegments[slot] != null) {
                continue;
            }
            final int targetSlot = slot;
            final int targetIndex = mIndex + (slot - 2);
            if (targetIndex < 0 || targetIndex >= mPages.size()) {
                continue;
            }
            final File file = mPages.get(targetIndex);
            if (MangaSettings.hasExtension(file.getName(), mSettings.videoExtensions())) {
                continue;
            }
            final String key = stripKey(targetIndex);
            Bitmap cached = mCache.get(key);
            if (cached != null && !cached.isRecycled()) {
                mStripSegments[targetSlot] = cached;
                updateStripTransform();
                continue;
            }
            mExecutor.execute(() -> {
                final Bitmap bitmap = decodePage(file);
                if (bitmap != null) {
                    mCache.put(key, bitmap);
                }
                mMainHandler.post(() -> {
                    if (mDestroyed || generation != mStripGeneration || !isContinuous()) {
                        return;
                    }
                    int expected = mIndex + (targetSlot - 2);
                    if (expected == targetIndex) {
                        mStripSegments[targetSlot] = bitmap;
                        updateStripTransform();
                    }
                });
            });
        }
    }

    /** Recomputes the shared scale and pushes the five pages into the strip
     *  view. All pages use the CURRENT page's width scale, so crossing a seam
     *  never causes a sudden size jump. */
    private void updateStripTransform() {
        if (mStripView == null || !isContinuous()) {
            return;
        }
        Bitmap cur = mStripSegments[2];
        if (cur == null && mCurrent != null) {
            mStripSegments[2] = mCurrent;
            cur = mCurrent;
        }
        if (cur == null || cur.isRecycled()) {
            return;
        }
        float vw = mStripView.getWidth() > 0 ? mStripView.getWidth() : mPageImage.getWidth();
        if (vw <= 0) {
            // Still push the pages so scrolling has segment heights to work
            // with; a layout callback will refine the scale right after.
            mStripView.setStrip(mStripSegments, mStripScale, mZoom, mStripOffset);
            return;
        }
        float newScale = vw / (float) cur.getWidth();
        if (mStripScale > 0f && Math.abs(newScale - mStripScale) > 0.0001f) {
            mStripOffset *= newScale / mStripScale;
        }
        mStripScale = newScale;
        if (mStripLastZoom > 0f && Math.abs(mZoom - mStripLastZoom) > 0.0001f) {
            mStripOffset *= mZoom / mStripLastZoom;
        }
        mStripLastZoom = mZoom;
        mStripView.setStrip(mStripSegments, mStripScale, mZoom, mStripOffset);
    }

    /** Scrolls the long strip. Positive delta moves down the comic (content
     *  moves up). When the current page leaves the viewport the array slides by
     *  one page: the very same bitmaps are reused, so the seam is invisible. */
    private void scrollContinuous(float delta) {
        if (!isContinuous() || mStripSegments[2] == null) {
            return;
        }
        mStripOffset += delta;
        final float vh = mStripView.getHeight();
        for (int guard = 0; guard < 8; guard++) {
            float curH = mStripView.scaledHeight(2);
            if (curH <= 0f) {
                break;
            }
            if (mStripOffset >= curH) {
                if (mIndex + 1 >= mPages.size() || isVideoIndex(mIndex + 1)) {
                    mStripOffset = Math.max(0f, curH - Math.max(0f, vh));
                    break;
                }
                mStripOffset -= curH;
                shiftStrip(1);
                continue;
            }
            if (mStripOffset < 0f) {
                if (mIndex - 1 < 0 || isVideoIndex(mIndex - 1)) {
                    mStripOffset = 0f;
                    break;
                }
                shiftStrip(-1);
                float prevH = mStripView.scaledHeight(2);
                mStripOffset += prevH;
                continue;
            }
            break;
        }
        mStripView.setOffset(mStripOffset);
    }

    private boolean isVideoIndex(int index) {
        return index >= 0 && index < mPages.size()
                && MangaSettings.hasExtension(mPages.get(index).getName(),
                        mSettings.videoExtensions());
    }

    /** Slides the five-page window by one page without reloading the visible
     *  bitmaps. Only the new far end needs decoding. */
    private void shiftStrip(int delta) {
        if (delta > 0) {
            for (int i = 0; i < 4; i++) {
                mStripSegments[i] = mStripSegments[i + 1];
            }
            mStripSegments[4] = null;
            mIndex++;
        } else {
            for (int i = 4; i > 0; i--) {
                mStripSegments[i] = mStripSegments[i - 1];
            }
            mStripSegments[0] = null;
            mIndex--;
        }
        mCurrent = mStripSegments[2];
        mPageCounter.setText((mIndex + 1) + " / " + mPages.size());
        if (mSettings.bool(MangaSettings.PROGRESS_ENABLED)) {
            progressPrefs().edit().putInt(pageProgressKey(), mIndex).apply();
        }
        applyDisplaySettings();
        // Keep the SAME scale and offset across a seam: recomputing the width
        // scale here would rescale the offset and make the picture jump.
        mStripView.setStrip(mStripSegments, mStripScale, mZoom, mStripOffset);
        loadStripNeighbours(mStripGeneration);
        Log.d(TAG, "continuous shift to " + (mIndex + 1) + "/" + mPages.size());
    }

    // ---------------------------------------------------------- settings

    private void applyDisplaySettings() {
        if (mRootView != null) {
            mRootView.setBackgroundColor(mSettings.backgroundColor());
            mRootView.setKeepScreenOn(mSettings.bool(MangaSettings.READER_KEEP_AWAKE));
        }
        if (mBrightnessOverlay != null) {
            int brightness = mSettings.integer(MangaSettings.READER_BRIGHTNESS);
            mBrightnessOverlay.setAlpha(Math.max(0f, Math.min(0.8f,
                    (100 - brightness) / 100.0f * 0.8f)));
        }
        if (mPageCounter != null) {
            mPageCounter.setVisibility(mSettings.bool(MangaSettings.READER_SHOW_PAGE_NUMBER)
                    ? VISIBLE : GONE);
        }
    }

    private void applyImageTransform() {
        if (isContinuous()) {
            updateStripTransform();
            return;
        }
        float bw = mDisplayWidth;
        float bh = mDisplayHeight;
        float vw = (mTiledPage && mStripView.getWidth() > 0)
                ? mStripView.getWidth() : mPageImage.getWidth();
        float vh = (mTiledPage && mStripView.getHeight() > 0)
                ? mStripView.getHeight() : mPageImage.getHeight();
        if (bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0) {
            return;
        }
        int mode = mSettings.integer(MangaSettings.READER_SCALE);
        int direction = mSettings.integer(MangaSettings.READER_DIRECTION);
        float sx;
        float sy;
        if (direction == 2) {
            sx = sy = vw / bw;
        } else {
            switch (mode) {
                case 1:
                    sx = vw / bw;
                    sy = vh / bh;
                    break;
                case 2:
                    sx = sy = Math.max(vw / bw, vh / bh);
                    break;
                case 3:
                    sx = sy = 1.0f;
                    break;
                default:
                    sx = sy = Math.min(vw / bw, vh / bh);
                    break;
            }
        }
        sx *= mZoom;
        sy *= mZoom;
        float dx = (vw - bw * sx) / 2.0f;
        float dy = (vh - bh * sy) / 2.0f;
        if (direction == 2) {
            dy = Math.max(vh - bh * sy, Math.min(0.0f, -mScrollY));
            if (vh >= bh * sy) {
                dy = (vh - bh * sy) / 2.0f;
            }
        }
        mMatrix.setScale(sx, sy);
        mMatrix.postTranslate(dx, dy);
        if (mTiledPage) {
            mStripView.setTileMatrix(mMatrix);
        } else {
            mPageImage.setImageMatrix(mMatrix);
        }
    }

    private void applyTransition() {
        int type = mSettings.integer(MangaSettings.READER_TRANSITION);
        int direction = mSettings.integer(MangaSettings.READER_DIRECTION);
        Animation animation = null;
        if (type == 1) {
            animation = new AlphaAnimation(0.3f, 1.0f);
            animation.setDuration(220);
        } else if (type == 2) {
            float offset = direction == 1 ? 0.12f : -0.12f;
            animation = new TranslateAnimation(
                    Animation.RELATIVE_TO_SELF, offset, Animation.RELATIVE_TO_SELF, 0f,
                    Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f);
            animation.setDuration(220);
        }
        if (animation != null && mStage != null) {
            mStage.startAnimation(animation);
        }
    }

    private void scheduleAuto() {
        cancelAuto();
        if (mDestroyed || !mSettings.bool(MangaSettings.READER_AUTO_ADVANCE)
                || mPages.isEmpty() || isVideoPage()) {
            return;
        }
        int seconds = Math.max(2, mSettings.integer(MangaSettings.READER_AUTO_ADVANCE_SEC));
        mMainHandler.postDelayed(mAutoTick, seconds * 1000L);
        notifyTrayAuto(true);
    }

    private void cancelAuto() {
        mMainHandler.removeCallbacks(mAutoTick);
        notifyTrayAuto(false);
    }

    private void onAutoTick() {
        if (mDestroyed || !isVisible() || !mSettings.bool(MangaSettings.READER_AUTO_ADVANCE)) {
            return;
        }
        if (mIndex + 1 < mPages.size()) {
            showPage(mIndex + 1);
        } else {
            notifyTrayAuto(false);
            showHint("已到最后一页");
        }
    }

    private void notifyTrayAuto(boolean active) {
        Context context = getContext();
        if (context instanceof VRBrowserActivity) {
            TrayWidget tray = ((VRBrowserActivity) context).getTray();
            if (tray != null) {
                tray.setReaderAutoActive(active);
            }
        }
    }

    private boolean isVideoPage() {
        return mIndex >= 0 && mIndex < mPages.size()
                && MangaSettings.hasExtension(mPages.get(mIndex).getName(),
                mSettings.videoExtensions());
    }

    // ------------------------------------------------------------ controls

    public void pageDelta(int delta) {
        int direction = mSettings.integer(MangaSettings.READER_DIRECTION);
        if (direction == 1) {
            delta = -delta;
        }
        showPage(mIndex + delta);
    }

    public void cycleScale() {
        int value = (mSettings.integer(MangaSettings.READER_SCALE) + 1) % 4;
        mSettings.put(MangaSettings.READER_SCALE, value);
        mZoom = 1.0f;
        applyImageTransform();
        String[] names = {"适应屏幕", "拉伸填满", "居中裁剪", "原始尺寸"};
        showHint("缩放模式：" + names[value]);
    }

    public void cycleDirection() {
        int value = (mSettings.integer(MangaSettings.READER_DIRECTION) + 1) % 3;
        mSettings.put(MangaSettings.READER_DIRECTION, value);
        mScrollY = 0.0f;
        String[] names = {"从左到右", "从右到左", "纵向连续"};
        showHint("阅读方向：" + names[value]);
        if (!mPages.isEmpty()) {
            showPage(mIndex);
        }
    }

    public void cycleBrightness() {
        int[] levels = {40, 60, 80, 100};
        int current = mSettings.integer(MangaSettings.READER_BRIGHTNESS);
        int next = levels[0];
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] == current) {
                next = levels[(i + 1) % levels.length];
                break;
            }
        }
        mSettings.put(MangaSettings.READER_BRIGHTNESS, next);
        applyDisplaySettings();
        showHint("亮度：" + next + "%");
    }

    public void toggleAutoAdvance() {
        boolean value = !mSettings.bool(MangaSettings.READER_AUTO_ADVANCE);
        mSettings.put(MangaSettings.READER_AUTO_ADVANCE, value);
        cancelAuto();
        if (value) {
            scheduleAuto();
        }
        showHint(value ? "自动翻页：开" : "自动翻页：关");
    }

    public void openGotoDialog() {
        if (mPages.isEmpty()) {
            return;
        }
        mGotoDialog.setVisibility(VISIBLE);
        mGotoInput.setText(String.valueOf(mIndex + 1));
        mGotoInput.setHint("1 - " + mPages.size());
        mGotoInput.requestFocus();
        mGotoInput.selectAll();
    }

    private void confirmGoto() {
        try {
            int page = Integer.parseInt(mGotoInput.getText().toString().trim());
            if (page >= 1 && page <= mPages.size()) {
                mGotoDialog.setVisibility(GONE);
                showPage(page - 1);
            } else {
                Toast.makeText(getContext(), "页码范围 1 - " + mPages.size(),
                        Toast.LENGTH_SHORT).show();
            }
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), "请输入页码", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleTap(float x) {
        if (isVideoPage() && (mMediaPlayer == null || !isVideoPlaying())) {
            playVideo(mPages.get(mIndex));
            return;
        }
        if (!mSettings.bool(MangaSettings.READER_TAP_ZONES)) {
            return;
        }
        float third = (mStage != null ? mStage.getWidth() : getWidth()) / 3.0f;
        if (x < third) {
            pageDelta(-1);
        } else if (x > third * 2.0f) {
            pageDelta(1);
        } else if (mPageCounter != null) {
            mPageCounter.setVisibility(mPageCounter.getVisibility() == VISIBLE ? GONE : VISIBLE);
        }
    }

    private boolean isVideoPlaying() {
        try {
            return mMediaPlayer != null && mMediaPlayer.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    // ----------------------------------------------------- double page / joystick

    /** Toggles two-up (double page) reading and reloads the current spread. */
    public void toggleDoublePage() {
        mDoublePage = !mDoublePage;
        mSettings.put(MangaSettings.READER_DOUBLE_PAGE, mDoublePage);
        showHint(mDoublePage ? "双页：开" : "双页：关");
        if (!mPages.isEmpty()) {
            showPage(mIndex);
        }
    }

    // --------------------------------------------------- external control API

    /** TEMPORARY image zoom: scales only the picture content inside a
     *  fixed-size window. showPage() resets mZoom, so the next page is 100%. */
    public void zoomBy(float factor) {
        mZoom = clamp(mZoom * factor, 0.2f, 8.0f);
        markZoomGesture();
        applyImageTransform();
        showHint("图片缩放：" + Math.round(mZoom * 100) + "%（切页重置）");
    }

    /** PERSISTENT canvas zoom: resizes the widget itself (worldWidth / width /
     *  height). Saved to prefs, so it survives page changes and restarts. */
    public void scaleCanvasBy(float factor) {
        markZoomGesture();
        mUserScale = clamp(mUserScale * factor, 0.4f, 3.0f);
        mSettings.put(MangaSettings.READER_CANVAS_SCALE, mUserScale);
        applyUserScale();
        showHint("画布大小：" + Math.round(mUserScale * 100) + "%（持久）");
    }

    /** Re-apply the default placement, window size and image zoom. */
    public void resetPosition() {
        mUserScale = 1.0f;
        mSettings.put(MangaSettings.READER_CANVAS_SCALE, 1.0f);
        mZoom = 1.0f;
        mScrollY = 0.0f;
        mStripOffset = 0.0f;
        mStripLastZoom = 1.0f;
        Context context = getContext();
        int windowWidth = SettingsStore.getInstance(context).getWindowWidth();
        int windowHeight = SettingsStore.getInstance(context).getWindowHeight();
        WidgetPlacement p = getPlacement();
        float previousY = p.translationY;
        p.worldWidth = WidgetPlacement.floatDimension(context, R.dimen.window_world_width)
                * (float) windowWidth / (float) SettingsStore.WINDOW_WIDTH_DEFAULT * 0.60f;
        p.width = (int) (windowWidth * 0.78f) + mBorderWidth * 2;
        p.height = (int) (windowHeight * 0.82f) + mBorderWidth * 2;
        float readerWorldHeight = p.worldWidth
                * ((float) p.height / (float) p.width) * 360.0f;
        float trayY = WidgetPlacement.unitFromMeters(context, R.dimen.tray_world_y);
        p.translationY = (trayY + 60.0f) + readerWorldHeight * 0.5f;
        p.translationZ = WidgetPlacement.getWindowWorldZMeters(context) + 220.0f;
        mWidgetManager.updateWidget(this);
        notifyCanvasResized(p.translationY - previousY);
        applyImageTransform();
        showHint("已重置位置/大小");
    }

    /** Jump to the previous/next sibling folder (chapter) of the current one. */
    public void openSiblingFolder(int delta) {
        if (mFolder == null || mFolder.getParentFile() == null) {
            showHint("无法切换文件夹");
            return;
        }
        File parent = mFolder.getParentFile();
        File[] dirs = parent.listFiles((FileFilter) f -> f.isDirectory());
        if (dirs == null || dirs.length == 0) {
            showHint("没有同级文件夹");
            return;
        }
        List<File> list = new ArrayList<>();
        for (File d : dirs) {
            list.add(d);
        }
        Collections.sort(list, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getAbsolutePath().equals(mFolder.getAbsolutePath())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            showHint("当前文件夹不在父目录中");
            return;
        }
        int next = (idx + delta + list.size()) % list.size();
        setFolder(list.get(next));
        showHint("文件夹：" + list.get(next).getName());
    }

    public void setResizeModifier(boolean on) {
        if (on == mResizeModifier) {
            return;
        }
        mResizeModifier = on;
        if (on) {
            startJoyTick();
        } else {
            stopJoyTick();
        }
    }

    /** Execute a bound action (dispatched from VRBrowserActivity). */
    public void dispatchMangaAction(String action) {
        switch (action) {
            case MangaSettings.ACTION_PREV_PAGE:
                pageDelta(-1);
                break;
            case MangaSettings.ACTION_NEXT_PAGE:
                pageDelta(1);
                break;
            case MangaSettings.ACTION_PREV_FOLDER:
                openSiblingFolder(-1);
                break;
            case MangaSettings.ACTION_NEXT_FOLDER:
                openSiblingFolder(1);
                break;
            case MangaSettings.ACTION_ZOOM_IN:
                zoomBy(1.2f);
                break;
            case MangaSettings.ACTION_ZOOM_OUT:
                zoomBy(1.0f / 1.2f);
                break;
            case MangaSettings.ACTION_CANVAS_ZOOM_IN:
                scaleCanvasBy(1.15f);
                break;
            case MangaSettings.ACTION_CANVAS_ZOOM_OUT:
                scaleCanvasBy(1.0f / 1.15f);
                break;
            case MangaSettings.ACTION_RESET_POS:
                resetPosition();
                break;
            case MangaSettings.ACTION_TOGGLE_DOUBLE:
                toggleDoublePage();
                break;
            default:
                break;
        }
    }

    private String displayKey(int index) {
        return (mDoublePage ? "d:" : "s:") + index;
    }

    /** Builds the bitmap to display for a page index (single, or a 2-page spread). */
    private Bitmap decodeDisplay(int index) {
        if (mDoublePage && mSettings.integer(MangaSettings.READER_DIRECTION) != 2) {
            Bitmap bmA = decodePage(mPages.get(index));
            if (bmA != null && index + 1 < mPages.size()) {
                Bitmap bmB = decodePage(mPages.get(index + 1));
                if (bmB != null) {
                    boolean aPortrait = bmA.getHeight() >= bmA.getWidth();
                    boolean bPortrait = bmB.getHeight() >= bmB.getWidth();
                    // Two portrait pages side by side; a landscape page forces single.
                    if (aPortrait && bPortrait) {
                        return combineSideBySide(bmA, bmB);
                    }
                }
            }
            return bmA;
        }
        return decodePage(mPages.get(index));
    }

    private Bitmap combineSideBySide(Bitmap a, Bitmap b) {
        try {
            int h = Math.max(a.getHeight(), b.getHeight());
            int wa = Math.round(a.getWidth() * ((float) h / a.getHeight()));
            int wb = Math.round(b.getWidth() * ((float) h / b.getHeight()));
            // No edge cap any more: the old 4096 limit halved ordinary
            // spreads and made zoomed text blurry. Only guard against Canvas'
            // single-bitmap draw limit, and even then scale by area (sqrt),
            // which preserves far more detail than clamping one edge.
            int totalW = wa + wb;
            long bytes = (long) totalW * h * 4L;
            if (bytes > SINGLE_BITMAP_LIMIT) {
                double scale = Math.sqrt((double) SINGLE_BITMAP_LIMIT / (double) bytes);
                h = Math.max(1, (int) (h * scale));
                wa = Math.max(1, (int) (wa * scale));
                wb = Math.max(1, (int) (wb * scale));
            }
            Bitmap out = Bitmap.createBitmap(wa + wb, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawBitmap(a, null, new Rect(0, 0, wa, h), null);
            c.drawBitmap(b, null, new Rect(wa, 0, wa + wb, h), null);
            return out;
        } catch (Throwable t) {
            return a;
        }
    }

    // ------------------------------------------------------------- controller

    /** Native thumbstick deltas (VRBrowser::HandleScrollEvent) land here.
     *  Wolvic never forwards gamepad AXIS_X/Y to the widget's generic-motion
     *  listener in VR, so this top-level route is the only reliable one. The
     *  values are per-frame axis samples (roughly -1..1), not absolute stick
     *  positions, so accumulate them for discrete page turns / zoom steps. */
    public void onControllerScroll(float dx, float dy) {
        if (mDestroyed || !isVisible()) {
            return;
        }
        if (Math.abs(dx) < 0.001f && Math.abs(dy) < 0.001f) {
            return;
        }
        mScrollAccumX += dx;
        mScrollAccumY += dy;

        // While a resize / zoom gesture is active the stick's unavoidable
        // horizontal component must NOT be interpreted as a page turn.
        boolean pagingSuspended = mResizeModifier || zoomGestureActive();
        if (mResizeModifier) {
            markZoomGesture();
            mUserScale = clamp(mUserScale * (1.0f - dy * 0.012f), 0.4f, 3.0f);
            WidgetPlacement p = getPlacement();
            float baseZ = WidgetPlacement.getWindowWorldZMeters(getContext()) + 220.0f;
            p.translationZ = clamp(p.translationZ - dx * 3.0f, baseZ - 200.0f, baseZ + 400.0f);
            applyUserScale();
        } else {
            int direction = mSettings.integer(MangaSettings.READER_DIRECTION);
            if (direction == 2) {
                // Seamless long-strip scroll: dy > 0 scrolls down into the
                // next page, dy < 0 scrolls up into the previous page's tail.
                if (isContinuous()) {
                    scrollContinuous(-dy * 14.0f * scrollSpeed());
                } else {
                    mScrollY -= dy * 14.0f * scrollSpeed();
                    applyImageTransform();
                }
            } else if (Math.abs(mScrollAccumY) > 0.35f) {
                zoomBy(mScrollAccumY > 0 ? 0.89f : 1.12f);
                mScrollAccumY = 0f;
            }
        }

        if (pagingSuspended) {
            // Discard the accumulated horizontal drift instead of letting it
            // fire as soon as the modifier is released.
            mScrollAccumX = 0f;
            mMainHandler.removeCallbacks(mScrollDecay);
            mMainHandler.postDelayed(mScrollDecay, 220);
            return;
        }
        if (!mJoyPageCooldown && Math.abs(mScrollAccumX) > 0.35f) {
            pageDelta(mScrollAccumX > 0 ? -1 : 1);
            mScrollAccumX = 0f;
            mJoyPageCooldown = true;
            mMainHandler.postDelayed(() -> mJoyPageCooldown = false, 350);
        }

        // A released stick stops sending events, so expire the accumulator.
        mMainHandler.removeCallbacks(mScrollDecay);
        mMainHandler.postDelayed(mScrollDecay, 220);
    }

    /** True for ~400 ms after the last zoom/resize input. */
    private boolean zoomGestureActive() {
        return System.currentTimeMillis() < mZoomGestureUntil;
    }

    private void markZoomGesture() {
        mZoomGestureUntil = System.currentTimeMillis() + 400L;
    }

    /** User-tunable continuous-mode thumbstick scroll multiplier. */
    private float scrollSpeed() {
        return clamp(mSettings.getFloat(MangaSettings.READER_SCROLL_SPEED, 2.0f),
                0.25f, 8.0f);
    }

    private static boolean isResizeKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_R1
                || keyCode == KeyEvent.KEYCODE_BUTTON_L1
                || keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL
                || keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR
                || keyCode == KeyEvent.KEYCODE_BUTTON_MODE;
    }

    private boolean onJoyMotion(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_MOVE) {
            float x = e.getAxisValue(MotionEvent.AXIS_X);
            float y = e.getAxisValue(MotionEvent.AXIS_Y);
            float hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(hatX) > Math.abs(x)) x = hatX;
            if (Math.abs(hatY) > Math.abs(y)) y = hatY;
            mAxisX = x;
            mAxisY = y;
            if (Math.abs(x) > 0.15f || Math.abs(y) > 0.15f) {
                startJoyTick();
            }
        }
        return true;
    }

    private void startJoyTick() {
        if (mJoyActive) {
            return;
        }
        mJoyActive = true;
        mMainHandler.post(mJoyTick);
    }

    private void stopJoyTick() {
        mJoyActive = false;
        mMainHandler.removeCallbacks(mJoyTick);
    }

    private void joyTick() {
        if (mDestroyed || !isVisible()) {
            mJoyActive = false;
            return;
        }
        float dead = 0.20f;
        boolean moving = Math.abs(mAxisX) > dead || Math.abs(mAxisY) > dead;
        if (!moving) {
            mJoyActive = false;
            return;
        }
        if (mResizeModifier) {
            // Hold a controller side button + joystick to resize / reposition window.
            markZoomGesture();
            mUserScale = clamp(mUserScale * (1.0f - mAxisY * 0.05f), 0.4f, 3.0f);
            WidgetPlacement p = getPlacement();
            float baseZ = WidgetPlacement.getWindowWorldZMeters(getContext()) + 220.0f;
            p.translationZ = clamp(p.translationZ - mAxisX * 8.0f, baseZ - 200.0f, baseZ + 400.0f);
            applyUserScale();
        } else {
            int direction = mSettings.integer(MangaSettings.READER_DIRECTION);
            if (direction == 2) {
                // Vertical continuous scroll, like swiping a long image.
                if (isContinuous()) {
                    scrollContinuous(-mAxisY * 50.0f * scrollSpeed());
                } else {
                    mScrollY -= mAxisY * 50.0f * scrollSpeed();
                    applyImageTransform();
                }
            } else if (!mJoyPageCooldown && !zoomGestureActive()) {
                if (mAxisY < -dead) {
                    pageDelta(1);
                    mJoyPageCooldown = true;
                    mMainHandler.postDelayed(() -> mJoyPageCooldown = false, 350);
                } else if (mAxisY > dead) {
                    pageDelta(-1);
                    mJoyPageCooldown = true;
                    mMainHandler.postDelayed(() -> mJoyPageCooldown = false, 350);
                }
            }
        }
        mMainHandler.postDelayed(mJoyTick, 50);
    }

    private void applyUserScale() {
        WidgetPlacement p = getPlacement();
        Context context = getContext();
        int windowWidth = SettingsStore.getInstance(context).getWindowWidth();
        int windowHeight = SettingsStore.getInstance(context).getWindowHeight();
        float baseWorld = WidgetPlacement.floatDimension(context, R.dimen.window_world_width)
                * (float) windowWidth / (float) SettingsStore.WINDOW_WIDTH_DEFAULT * 0.60f;

        // Remember the CURRENT bottom edge (which may have been moved by a
        // drag) and keep it pinned: zooming grows the canvas upwards only, so
        // it can never slide down behind the grab bar / control bar.
        float previousY = p.translationY;
        float previousHeight = p.worldWidth
                * ((float) p.height / (float) Math.max(1, p.width)) * 360.0f;
        float bottomY = previousY - previousHeight * 0.5f;

        p.worldWidth = baseWorld * mUserScale;
        p.width = (int) (windowWidth * 0.78f * mUserScale) + mBorderWidth * 2;
        p.height = (int) (windowHeight * 0.82f * mUserScale) + mBorderWidth * 2;
        float readerWorldHeight = p.worldWidth
                * ((float) p.height / (float) p.width) * 360.0f;
        p.translationY = bottomY + readerWorldHeight * 0.5f;
        mWidgetManager.updateWidget(this);
        notifyCanvasResized(p.translationY - previousY);
    }

    /** The tray and the floating grab bar are children of this surface. When
     *  its centre moves up during a canvas zoom, their relative translation
     *  must be corrected by the same amount so they stay put on screen. */
    private void notifyCanvasResized(float deltaParentY) {
        if (Math.abs(deltaParentY) < 0.01f) {
            return;
        }
        Context context = getContext();
        if (context instanceof VRBrowserActivity) {
            ((VRBrowserActivity) context).onMangaCanvasResized(deltaParentY);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public void persistState() {
        MediaPlayer player = mMediaPlayer;
        if (player != null && !mVideoCompleted && mSettings.bool(MangaSettings.PROGRESS_ENABLED)
                && mSettings.bool(MangaSettings.PROGRESS_VIDEO)
                && mIndex >= 0 && mIndex < mPages.size()) {
            try {
                progressPrefs().edit()
                        .putInt(videoProgressKey(mPages.get(mIndex)), player.getCurrentPosition())
                        .apply();
            } catch (Exception ignored) {
            }
        }
        if (mFolder != null && mSettings.bool(MangaSettings.PROGRESS_ENABLED)) {
            progressPrefs().edit().putInt(pageProgressKey(), mIndex).apply();
        }
        Log.i(TAG, "persistState index=" + mIndex);
    }

    private void showHint(String message) {
        if (mHint == null) {
            return;
        }
        mHint.setText(message);
        mHint.setVisibility(VISIBLE);
        mMainHandler.removeCallbacks(mHideHint);
        mMainHandler.postDelayed(mHideHint, 1800);
    }

    // -------------------------------------------------------------- video

    private void playVideo(File page) {
        if (mVideoSurface == null || !mVideoSurface.isValid() || mDestroyed) {
            showHint("视频画面尚未就绪");
            return;
        }
        stopVideo(false);
        final MediaPlayer player = new MediaPlayer();
        mMediaPlayer = player;
        player.setOnPreparedListener(mp -> {
            if (player != mMediaPlayer || mDestroyed) {
                return;
            }
            if (mSettings.bool(MangaSettings.PROGRESS_ENABLED)
                    && mSettings.bool(MangaSettings.PROGRESS_VIDEO)
                    && mSettings.bool(MangaSettings.READER_VIDEO_RESUME)) {
                int saved = progressPrefs().getInt(videoProgressKey(page), 0);
                if (saved > 0 && saved < mp.getDuration()) {
                    mp.seekTo(saved);
                }
            }
            mp.start();
        });
        player.setOnCompletionListener(mp -> {
            if (player != mMediaPlayer || mDestroyed) {
                return;
            }
            mVideoCompleted = true;
            progressPrefs().edit().putInt(videoProgressKey(page), 0).apply();
            if (mSettings.bool(MangaSettings.READER_VIDEO_NEXT_ON_END)
                    && mIndex + 1 < mPages.size()) {
                showPage(mIndex + 1);
            }
        });
        player.setOnErrorListener((mp, what, extra) -> {
            if (player == mMediaPlayer && !mDestroyed) {
                stopVideo(false);
                showHint("视频无法播放：" + page.getName());
                if (mIndex + 1 < mPages.size()) {
                    showPage(mIndex + 1);
                }
            }
            return true;
        });
        try {
            player.setDataSource(page.getAbsolutePath());
            player.setSurface(mVideoSurface);
            player.prepareAsync();
        } catch (Exception e) {
            if (player == mMediaPlayer) {
                stopVideo(false);
                showHint("视频无法打开：" + page.getName());
            }
        }
    }

    private void stopVideo(boolean savePosition) {
        MediaPlayer player = mMediaPlayer;
        // Save BEFORE clearing mMediaPlayer, otherwise persistState() has no
        // player to read the current playback position from.
        if (savePosition) {
            persistState();
        }
        mMediaPlayer = null;
        if (player == null) {
            return;
        }
        try {
            player.setOnPreparedListener(null);
            player.setOnCompletionListener(null);
            player.setOnErrorListener(null);
            player.reset();
            player.release();
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------- lifecycle

    @Override
    protected void onDismiss() {
        Context context = getContext();
        if (context instanceof VRBrowserActivity) {
            ((VRBrowserActivity) context).closeMangaReader();
        }
    }

    @Override
    public void onPause() {
        persistState();
        MediaPlayer player = mMediaPlayer;
        if (player != null && isVideoPlaying()) {
            try {
                player.pause();
            } catch (Exception ignored) {
            }
        }
        cancelAuto();
    }

    @Override
    public void onResume() {
        updateWidget();
        MediaPlayer player = mMediaPlayer;
        if (isVisible() && player != null && !mVideoCompleted) {
            try {
                player.start();
            } catch (Exception ignored) {
            }
        }
        if (isVisible() && mSettings.bool(MangaSettings.READER_AUTO_ADVANCE)) {
            scheduleAuto();
        }
    }

    @Override
    public void setVisible(boolean aVisible) {
        super.setVisible(aVisible);
        if (!aVisible) {
            persistState();
            cancelAuto();
            MediaPlayer player = mMediaPlayer;
            if (player != null && isVideoPlaying()) {
                try {
                    player.pause();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void hide(@HideFlags int aHideFlags) {
        if (aHideFlags == REMOVE_WIDGET && !mDestroyed) {
            mDestroyed = true;
            mLoadGeneration++;
            cancelAuto();
            stopJoyTick();
            stopVideo(true);
            mExecutor.shutdownNow();
            mMainHandler.removeCallbacksAndMessages(null);
            if (mVideoSurface != null) {
                mVideoSurface.release();
                mVideoSurface = null;
            }
            mCache.evictAll();
        }
        super.hide(aHideFlags);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (mDestroyed) {
            return;
        }
        if (mVideoSurface != null) {
            mVideoSurface.release();
        }
        mVideoSurface = new Surface(surface);
        if (isVideoPage() && mSettings.bool(MangaSettings.READER_VIDEO_AUTOPLAY)) {
            playVideo(mPages.get(mIndex));
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        stopVideo(true);
        if (mVideoSurface != null) {
            mVideoSurface.release();
            mVideoSurface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    private SharedPreferences progressPrefs() {
        return getContext().getSharedPreferences(MangaSettings.PROGRESS_PREFS, Context.MODE_PRIVATE);
    }

    private String pageProgressKey() {
        return "page:" + (mFolder == null ? "" : mFolder.getAbsolutePath());
    }

    private static String videoProgressKey(File page) {
        return "video:" + page.getAbsolutePath();
    }
}
