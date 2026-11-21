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
import android.view.Gravity;
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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.igalia.wolvic.R;
import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.browser.SettingsStore;
import com.igalia.wolvic.manga.MangaChapterSession;
import com.igalia.wolvic.manga.MangaSettings;
import com.igalia.wolvic.manga.MangaImageDecoder;
import com.igalia.wolvic.ui.adapters.MangaCoverAdapter;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Independent floating reader window. It deliberately has no control bar: all
 * persistent controls are the original Wolvic tray buttons. Every manga setting
 * from the native SettingsWidget is consumed here (direction, scale, brightness,
 * crop, zoom, tap zones, volume keys, auto page advance, preload, transitions,
 * progress and video policy).
 */
public class MangaReaderWidget extends UIWidget implements TextureView.SurfaceTextureListener {

    private static final String TAG = "MangaReader";
    /** Android's hardware accelerated RecordingCanvas hard-refuses to draw a
     *  single Bitmap above ~100 MB: throwIfCannotDraw() kills the process with
     *  "Canvas: trying to draw too large(NNN bytes) bitmap". Measured on this
     *  device: a 7008x4672 page is 130,965,504 bytes and crashes instantly.
     *  Keep a wide margin. This is a DRAW limit, not a decode limit - pages
     *  above it are decoded at 1:1 into tiles, so no detail is lost. */
    private static final long SINGLE_BITMAP_LIMIT = 72L * 1024 * 1024;
    /** Each region tile must also stay far below the draw limit. */
    private static final long TILE_BYTES_LIMIT = 40L * 1024 * 1024;
    /** Above this size, holding every 1:1 tile at once would exhaust memory;
     *  fall back to a sampled single bitmap so the page still opens. */
    private static final long MAX_TILED_BYTES = 400L * 1024 * 1024;
    private static final int MAX_TILES = 8;

    private MangaSettings mSettings;
    private FrameLayout mRootView;
    private FrameLayout mStage;
    private LinearLayout mGotoDialog;
    private ImageView mPageImage;
    private TextureView mVideoTexture;
    private TextView mPageCounter;
    private TextView mHint;
    private View mBrightnessOverlay;
    /** Centered loading overlay: progress bar + speed, shown for every manga
     *  page / video while its bytes are still downloading. */
    private LinearLayout mLoadingOverlay;
    private TextView mLoadingLabel;
    private android.widget.ProgressBar mLoadingBar;
    private TextView mLoadingSpeed;
    private volatile long mLoadBytesDone;
    private volatile long mLoadBytesTotal;
    private volatile long mLoadBytesStartMs;
    private EditText mGotoInput;

    private final List<File> mPages = new ArrayList<>();
    /** Real page count reported by the downloader (its cached page-list size),
     *  independent of how many pages have landed on disk yet. -1 = unknown
     *  (local folders / plain directories), in which case mPages.size() is the
     *  truth. Used for the page counter denominator and chapter boundaries so
     *  a partially-downloaded chapter never looks like it ended early. */
    private int mPageTotal = -1;
    /** A next-page request that arrived while the target page was still
     *  downloading. Re-issued automatically once that page lands. */
    private int mPendingTargetPage = -1;
    private File mFolder;
    private int mIndex;
    private List<File> mChapterDirs;
    private int mChapterIndex;
    private String mChapterProgressKey;
    private MangaChapterSession.ChapterProvider mChapterProvider;
    private boolean mLoadingChapter;
    private boolean mChapterLoading;
    /** Bumped on every new chapter session. Async chapter downloads from an
     *  OLD session must never load their folder into the NEW manga - that was
     *  the "after switching manga it still shows the previous one" bug. */
    private volatile int mChapterSession;
    /** Chapter downloads can fail without invoking their callback; never let
     *  the "loading" flag block chapter crossing forever. */
    private volatile long mChapterLoadingSince;
    private float mPendingStripFrac;
    private final Runnable mChapterSaveTick = this::saveChapterProgress;
    private volatile int mLoadGeneration;
    private Bitmap mCurrent;
    private volatile boolean mDestroyed;
    private boolean mVideoCompleted;

    private MediaPlayer mMediaPlayer;
    private Surface mVideoSurface;
    private SurfaceTexture mVideoSurfaceTexture;

    private boolean mVideoPrepared;
    private File mVideoPage;
    private File mPendingVideoPage;
    private boolean mVideoPaused;
    private final android.graphics.RectF mVideoRect = new android.graphics.RectF();
    private final Rect mVideoStageOffset = new Rect();
    private boolean mVideoPointerInWidget;
    private float mVideoPointerX = -1f;
    private float mVideoPointerY = -1f;
    private String mLastVideoTransformLog;

    // Hover controls overlay the visible video bottom, in stage coordinates.
    private LinearLayout mVideoBar;
    private Button mVideoPlayButton;
    private Button mVideoFitButton;
    private TextView mVideoZoomText;
    private TextView mVideoTime;
    private SeekBar mVideoSeek;
    private int mVideoFitMode = 0;
    private float mVideoZoom = 1.0f;
    private int mVideoWidth = 0;
    private int mVideoHeight = 0;
    private boolean mVideoSeekDragging = false;
    // A seek (from stick or buttons) is asynchronous. While it is in flight,
    // getCurrentPosition() still returns the OLD position; without a lock the
    // 400ms tick pulls the UI progress bar straight back and the fast-forward
    // appears to fail. Hold the target position until seek completion.
    private volatile boolean mVideoSeeking = false;
    private volatile int mVideoSeekTargetMs = 0;
    private volatile int mVideoSeekGeneration = 0;
    private static final int SEEK_TOLERANCE_MS = 300;
    private static final long SEEK_MAX_WAIT_MS = 5000L;
    private volatile long mVideoSeekStartedAt = 0L;
    /** Releases the lock ONLY once the decoder has actually reached the target
     *  (or after a hard timeout). A plain 1500ms timer used to drop the lock
     *  while a slow/remote seek was still in flight, so the 400ms tick read the
     *  stale getCurrentPosition() and snapped the bar back = "fast-forward
     *  failed". */
    private final Runnable mClearVideoSeekLock = new Runnable() {
        @Override
        public void run() {
            MediaPlayer p = mMediaPlayer;
            if (!mVideoSeeking) return;
            boolean reached = false;
            if (p != null) {
                try {
                    reached = Math.abs(p.getCurrentPosition() - mVideoSeekTargetMs)
                            <= SEEK_TOLERANCE_MS;
                } catch (Exception ignored) {
                }
            }
            long waited = android.os.SystemClock.uptimeMillis() - mVideoSeekStartedAt;
            if (reached || waited > SEEK_MAX_WAIT_MS) {
                mVideoSeeking = false;
            } else {
                mMainHandler.postDelayed(this, 150L);
            }
        }
    };
    // The video bar hides on a delay so the pointer can travel into it (and
    // actually reach the seek bar) instead of vanishing on a brief glance.
    private static final long VIDEO_BAR_HIDE_DELAY_MS = 1000L;
    private final Runnable mHideVideoBarRunnable = () -> showVideoBar(false);
    // Progress geometry stays fixed when hover controls appear.
    private FrameLayout mVideoProgressTrack;
    private View mVideoProgressFill;
    private final Runnable mVideoTick = this::updateVideoProgress;

    private final Matrix mMatrix = new Matrix();
    private float mZoom = 1.0f;
    /** Pan offset of a zoomed image, in view pixels. Lets the user inspect
     *  the edges instead of only the centre of an enlarged page. */
    private float mPanX = 0.0f;
    private float mPanY = 0.0f;
    /** Last VR pointer position inside the picture panel. */
    private float mPointerX = -1f;
    private float mPointerY = -1f;
    private float mScrollY = 0.0f;
    private GestureDetector mGestureDetector;
    private LruCache<String, Bitmap> mCache;
    private final Map<String, PageLoad> mPendingPages = new HashMap<>();
    private final Set<String> mWindowKeys = new HashSet<>();
    private String mRequestedPageKey;

    // Continuous mode: five live pages [index-2 .. index+2] held as strong
    // references (an LruCache would evict them mid-scroll and break the seam).
    private MangaStripView mStripView;
    private View mReaderDragBar;
    private final Bitmap[] mStripSegments = new Bitmap[5];
    private float mStripOffset = 0.0f;
    private float mStripScale = 1.0f;
    private float mStripLastZoom = 1.0f;
    private int mStripGeneration;
    private long mLastStripRetry;
    private float mLastStripViewWidth = -1f;
    private int mLastStripSourceWidth = 0;

    private final ThreadPoolExecutor mExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "manga-decode");
                thread.setDaemon(true);
                return thread;
            });
    private final ThreadPoolExecutor mPreloadExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    runnable.run();
                }, "manga-page-window");
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
    private float mUserScale = 1.0f;           // single/double page + video
    private float mContinuousScale = 1.0f;     // independent continuous-mode zoom
    /** Paging from the thumbstick is suspended while a zoom gesture is in
     *  progress (and briefly afterwards), so a resize never flips pages. */
    private long mZoomGestureUntil = 0L;
    /** True when the current page is drawn as a stack of decoded tiles. */
    private boolean mTiledPage = false;
    /** Native dimensions of the current page, even when it is tiled. */
    private int mDisplayWidth = 0;
    private int mDisplayHeight = 0;
    /** Coalesces the per-frame canvas resize requests from a held thumbstick.
     *  Rebuilding the widget's SurfaceTexture on every frame is what made
     *  zooming crawl and flicker. */
    private boolean mScaleUpdatePending = false;
    private final Runnable mScaleUpdate = () -> {
        try {
            applyUserScale();
        } finally {
            // Never leave the flag stuck: a removed callback used to make all
            // later zoom requests no-ops until the reader was re-opened.
            mScaleUpdatePending = false;
        }
    };
    private long mLastZoomHint = 0L;
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
        mContinuousScale = clamp(
                mSettings.getFloat(MangaSettings.READER_CONTINUOUS_SCALE, 1.0f),
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
        mLoadingOverlay = findViewById(R.id.loadingOverlay);
        mLoadingLabel = findViewById(R.id.loadingLabel);
        mLoadingBar = findViewById(R.id.loadingBar);
        mLoadingSpeed = findViewById(R.id.loadingSpeed);
        mGotoInput = findViewById(R.id.gotoInput);
        mStripView = findViewById(R.id.stripView);
        mReaderDragBar = findViewById(R.id.readerDragBar);

        mPageImage.setScaleType(ImageView.ScaleType.MATRIX);
        mPageImage.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or_, ob) -> {
                    applyImageTransform();
                    if (isVideoPage()) {
                        applyVideoTransform();
                    }
                });
        if (mStripView != null) {
            mStripView.addOnLayoutChangeListener(
                    (v, l, t, r, b, ol, ot, or_, ob) -> updateStripTransform());
        }
        setupReaderDrag();
        mVideoTexture.setOpaque(false);
        mVideoTexture.setSurfaceTextureListener(this);
        mVideoTexture.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            if (isVideoPage()) {
                applyVideoTransform();
            }
        });
        // Each video opens fully fitted; only explicit controls write preferences.
        mVideoFitMode = 0;
        mVideoZoom = 1.0f;
        setupVideoBar();
        setupVideoProgressTrack();

        int cacheKb = Math.max(16, mSettings.integer(MangaSettings.CACHE_MB)) * 1024;
        File diskCacheBase = getContext().getExternalCacheDir();
        if (diskCacheBase == null) {
            diskCacheBase = getContext().getCacheDir();
        }
        MangaImageDecoder.setDiskCacheDir(new File(diskCacheBase, "avif-cache"));
        mCache = new LruCache<String, Bitmap>(cacheKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };

        mGestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                // Remember where the VR pointer is on the picture. Used as the
                // anchor for pointer-centred zoom and as the drag start.
                mPointerX = e.getX();
                mPointerY = e.getY();
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
                    boolean wasZoomed = mZoom > 1.01f;
                    zoomByAt(wasZoomed ? 1.0f / mZoom : 2.0f, e.getX(), e.getY());
                    showHint(!wasZoomed ? "已放大 2×" : "已还原缩放");
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                // Pointer + trigger drag on a ZOOMED image pans the picture,
                // just like dragging a photo on a phone. Clamped in
                // applyImageTransform() so no empty area can be exposed.
                if (mZoom > 1.01f && !isContinuous() && !mTiledPage) {
                    mPanX -= distanceX;
                    mPanY -= distanceY;
                    applyImageTransform();
                    return true;
                }
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
        if (mUserScale != 1.0f || mContinuousScale != 1.0f) {
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
        int netWidth = (int) (windowWidth * 0.78f);
        aPlacement.width = netWidth + mBorderWidth * 2;
        // This runs from the UIWidget constructor, before initialize() has set
        // mSettings, so read the preferences through a temporary instance.
        MangaSettings initSettings = MangaSettings.get(context);
        if (initSettings.integer(MangaSettings.READER_DIRECTION) == 2) {
            float aspect = MangaSettings.aspectRatio(clampAspect(
                    initSettings.integer(MangaSettings.READER_CONTINUOUS_ASPECT)));
            aPlacement.height = Math.round(netWidth / aspect) + mBorderWidth * 2;
        } else {
            aPlacement.height = (int) (windowHeight * 0.82f) + mBorderWidth * 2;
        }
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
        setFolder(folder, -1);
    }

    /** Opens a folder at the page matching a concrete file. Matching by path
     *  (not by list position) survives the reader's own video-first sorting
     *  and recursive chapter collection. */
    public void setFolder(File folder, String startFilePath) {
        setFolder(folder, -1);
        if (startFilePath == null) {
            return;
        }
        for (int i = 0; i < mPages.size(); i++) {
            if (mPages.get(i).getAbsolutePath().equals(startFilePath)) {
                showPage(i);
                return;
            }
        }
    }

    /** Opens a folder directly at a chosen page (used by the thumbnail view
     *  and "continue reading"). startIndex < 0 restores saved progress. */
    public void setFolder(File folder, int startIndex) {
        // Decide from the FOLDER, not the transient mLoadingChapter flag: a
        // chapter switch passes one of the session's own dirs, a genuinely new
        // manga passes something else. Previously mChapterDirs was cleared on
        // every setFolder() call with mLoadingChapter=false, which silently
        // dropped the playlist and made "next page" at the last page do
        // nothing (showPage() rejects an out-of-range index).
        boolean belongsToSession = false;
        if (mChapterDirs != null && folder != null) {
            for (File dir : mChapterDirs) {
                if (dir != null && dir.equals(folder)) {
                    belongsToSession = true;
                    break;
                }
            }
        }
        if (!mLoadingChapter && !belongsToSession) {
            // A fresh manga: invalidate in-flight async chapter downloads of
            // the previous manga and clear its picture immediately.
            mChapterSession++;
            mChapterLoading = false;
            mChapterDirs = null;
            mChapterProvider = null;
            mChapterProgressKey = null;
            mPendingStripFrac = 0f;
            mCurrent = null;
            mPageImage.setImageDrawable(null);
            if (mStripView != null) {
                mStripView.clearTiles();
                mStripView.setStrip(new Bitmap[5], 1.0f, 1.0f, 0.0f);
            }
        }
        stopVideo(true);
        cancelAuto();
        mLoadGeneration++;
        mFolder = folder;
        // Stale entries from another manga are the source of "old page shown
        // after switching". The cache is keyed by path now, but clearing also
        // releases several hundred MB before opening a new folder.
        if (mCache != null) {
            mCache.evictAll();
        }
        for (PageLoad load : mPendingPages.values()) {
            load.cancelled = true;
        }
        mPendingPages.clear();
        mPreloadExecutor.getQueue().clear();
        mWindowKeys.clear();
        mRequestedPageKey = null;
        mPages.clear();
        mPageTotal = -1;
        mPendingTargetPage = -1;
        mIndex = 0;
        mZoom = 1.0f;
        mPanX = 0f;
        mPanY = 0f;
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

        int saved = startIndex >= 0 ? startIndex
                : (folder == null || !mSettings.bool(MangaSettings.PROGRESS_ENABLED)
                        ? 0 : progressPrefs().getInt(pageProgressKey(), 0));
        Log.i(TAG, "setFolder pages=" + mPages.size() + " saved=" + saved);
        showPage(Math.max(0, Math.min(saved, Math.max(0, mPages.size() - 1))));
    }

    // ---------------------------------------------------- chapter playlist

    /** Opens a manga as an ordered chapter list. Boundary page turns and
     *  continuous scrolling cross into neighbouring chapters; online chapters
     *  are downloaded on demand and pre-cached ahead of the reading window. */
    public void setChapters(MangaChapterSession session) {
        mChapterSession++;
        mChapterLoading = false;
        // Clear the previous manga's picture immediately. Without this the
        // old frame stays on screen until the new page finishes decoding.
        mCurrent = null;
        mPageImage.setImageDrawable(null);
        if (mStripView != null) {
            mStripView.clearTiles();
            mStripView.setStrip(new Bitmap[5], 1.0f, 1.0f, 0.0f);
        }
        // Full reset like setFolder(): drop the old manga's page list and every
        // in-flight decode/download. Otherwise a manual page turn while the new
        // online chapter is still downloading falls back to the stale mPages
        // and shows the PREVIOUS manga until the user exits and re-enters.
        mPages.clear();
        mPageTotal = -1;
        mPendingTargetPage = -1;
        mIndex = 0;
        mLoadGeneration++;
        mStripGeneration++;
        mRequestedPageKey = null;
        mPendingStripFrac = 0f;
        for (PageLoad load : mPendingPages.values()) {
            load.cancelled = true;
        }
        mPendingPages.clear();
        if (mCache != null) {
            mCache.evictAll();
        }
        mPreloadExecutor.getQueue().clear();
        mExecutor.getQueue().removeIf(task -> task instanceof PageLoad);
        clearStripSegments();
        mChapterDirs = session.dirs;
        mChapterProvider = session.provider;
        mChapterProgressKey = session.progressKey;
        mChapterIndex = session.index;
        Log.i(TAG, "setChapters chapters=" + session.dirs.size() + " index=" + session.index
                + " page=" + session.page + " frac=" + session.frac);
        notifyTrayProgress();
        loadChapter(session.index, session.page, session.frac, session.startFilePath);
    }

    private void loadChapter(int index, int page, float frac, String startFilePath) {
        if (mChapterDirs == null || index < 0 || index >= mChapterDirs.size()) {
            return;
        }
        mChapterIndex = index;
        File dir = mChapterDirs.get(index);
        File[] files = dir.isDirectory() ? dir.listFiles() : null;
        boolean hasPages = countImageFiles(dir) > 0;
        final int session = mChapterSession;
        if (hasPages) {
            // Open immediately with whatever is already cached (fast first
            // page). If it is only a partial/prefetched head, top it up in the
            // background and grow the page list as pages land - this fixes
            // "only 4 images after switching chapters".
            startChapterLoading("正在加载第 " + (index + 1) + " 章…");
            openChapterDir(dir, page, frac, startFilePath);
            if (mChapterProvider != null) {
                mChapterProvider.ensureChapter(index, page, new MangaChapterSession.DirCallback() {
                    @Override
                    public void onDir(File d, String error) {
                        // The folder is already open; onProgress grows mPages.
                        hideLoading();
                    }
                    @Override
                    public void onProgress(int done, int total) {
                        mMainHandler.post(() -> {
                            if (session == mChapterSession && !mDestroyed) {
                                mPageTotal = total;
                                if (done < total) {
                                    updateChapterDownloadProgress(dir, done, total);
                                } else {
                                    hideLoading();
                                }
                                refreshChapterPageList();
                            }
                        });
                    }
                });
            }
            return;
        }
        if (mChapterProvider != null) {
            mChapterLoading = true;
            mChapterLoadingSince = android.os.SystemClock.uptimeMillis();
            startChapterLoading("正在加载第 " + (index + 1) + " 章…");
            showHint("加载第 " + (index + 1) + " 章…");
            mChapterProvider.ensureChapter(index, page, new MangaChapterSession.DirCallback() {
                private boolean opened;
                @Override
                public void onDir(File d, String error) {
                    mMainHandler.post(() -> {
                        if (session != mChapterSession || mDestroyed) {
                            return; // stale callback from a manga the user left
                        }
                        mChapterLoading = false;
                        if (d == null) {
                            hideLoading();
                            if (!opened) showHint("章节加载失败：" + error);
                            return;
                        }
                        mChapterDirs.set(index, d);
                        // onProgress may have already opened the folder on the
                        // first landed page. Re-opening here would snap the
                        // reader back to the start page mid-reading.
                        if (!opened) {
                            opened = true;
                            openChapterDir(d, page, frac, startFilePath);
                        }
                    });
                }
                @Override
                public void onProgress(int done, int total) {
                    mMainHandler.post(() -> {
                        if (session != mChapterSession || mDestroyed
                                || mChapterDirs == null || index >= mChapterDirs.size()) {
                            return;
                        }
                        mPageTotal = total;
                        updateChapterDownloadProgress(mChapterDirs.get(index), done, total);
                        if (!opened) {
                            // First page landed: show it now instead of making
                            // the user stare at a hint for the whole chapter.
                            File d = mChapterDirs.get(index);
                            if (d != null && countImageFiles(d) > 0) {
                                opened = true;
                                mChapterLoading = false;
                                hideLoading();
                                mChapterDirs.set(index, d);
                                openChapterDir(d, page, frac, startFilePath);
                            }
                        } else {
                            // Already open: grow the page list as the tail lands.
                            if (done >= total) hideLoading();
                            refreshChapterPageList();
                        }
                    });
                }
            });
        } else {
            openChapterDir(dir, page, frac, startFilePath);
        }
    }

    /** Counts only readable image/video files, ignoring .tmp and 0-byte files
     *  left by an interrupted download (a folder of those is NOT "ready"). */
    private int countImageFiles(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return 0;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        String[] imageExt = mSettings.imageExtensions();
        String[] videoExt = mSettings.videoExtensions();
        int n = 0;
        for (File f : files) {
            if (f != null && f.isFile() && f.length() > 0 && !f.getName().endsWith(".tmp")
                    && (MangaSettings.hasExtension(f.getName(), imageExt)
                        || MangaSettings.hasExtension(f.getName(), videoExt))) {
                n++;
            }
        }
        return n;
    }

    /** Re-scans the currently open online chapter folder and appends pages that
     *  finished downloading after the folder was first opened. Keeps the
     *  current page index, so progressive download never yanks the view. */
    private void refreshChapterPageList() {
        if (mDestroyed || mFolder == null || mChapterDirs == null || mChapterIndex < 0
                || mChapterIndex >= mChapterDirs.size()) {
            return;
        }
        File current = mPages.isEmpty() ? null : mPages.get(Math.min(mIndex, mPages.size() - 1));
        List<File> videos = new ArrayList<>();
        List<File> images = new ArrayList<>();
        String[] imageExt = mSettings.imageExtensions();
        String[] videoExt = mSettings.videoExtensions();
        collectFiles(mFolder, mSettings.bool(MangaSettings.SOURCE_RECURSIVE) ? 2 : 0,
                imageExt, videoExt, images, videos);
        boolean natural = mSettings.bool(MangaSettings.SOURCE_NATURAL_SORT);
        java.util.Comparator<File> comparator = natural
                ? (a, b) -> MangaCoverAdapter.naturalCompare(a.getName(), b.getName())
                : (a, b) -> a.getName().compareToIgnoreCase(b.getName());
        videos.sort(comparator);
        images.sort(comparator);
        List<File> all = new ArrayList<>();
        if (mSettings.bool(MangaSettings.SOURCE_VIDEO_FIRST)) {
            all.addAll(videos);
            all.addAll(images);
        } else {
            all.addAll(videos);
            all.addAll(images);
            all.sort(comparator);
        }
        if (all.size() <= mPages.size()) {
            return; // nothing new landed
        }
        int keepIndex = 0;
        if (current != null) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).equals(current)) {
                    keepIndex = i;
                    break;
                }
            }
        }
        mPages.clear();
        mPages.addAll(all);
        mIndex = keepIndex;
        // If the user asked for a page that was still downloading, take them
        // there now that it has landed - do NOT snap back to keepIndex.
        boolean jumped = false;
        if (mPendingTargetPage >= 0 && mPendingTargetPage < mPages.size()) {
            mIndex = mPendingTargetPage;
            mPendingTargetPage = -1;
            jumped = true;
        }
        mPageCounter.setText(counterText());
        notifyTrayProgress();
        if (jumped) {
            showPage(mIndex);
        } else {
            preloadNext();
        }
        Log.i(TAG, "chapter pages grew to " + mPages.size() + " keepIndex=" + keepIndex
                + " jumped=" + jumped);
    }

    private void openChapterDir(File dir, int page, float frac, String startFilePath) {
        mPendingStripFrac = frac;
        mLoadingChapter = true;
        try {
            if (startFilePath != null) {
                setFolder(dir, startFilePath);
            } else {
                setFolder(dir, page);
            }
        } finally {
            mLoadingChapter = false;
        }
    }

    private boolean hasChapter(int index) {
        return mChapterDirs != null && index >= 0 && index < mChapterDirs.size();
    }

    /** Crosses a chapter boundary. delta=+1 lands on the next chapter's first
     *  page, delta=-1 on the previous chapter's last page (continuous mode:
     *  its bottom, so up-scrolling keeps reading the previous chapter tail). */
    private void chapterLoadingGuard() {
        if (mChapterLoading && android.os.SystemClock.uptimeMillis()
                - mChapterLoadingSince > 20000L) {
            mChapterLoading = false;
            Log.w(TAG, "chapter loading timed out, resetting flag");
        }
    }

    public void chapterDelta(int delta) {
        chapterLoadingGuard();
        advanceChapter(delta);
    }

    private void advanceChapter(int delta) {
        chapterLoadingGuard();
        if (mChapterDirs == null || mChapterDirs.size() <= 1 || mChapterLoading) {
            showHint(delta > 0 ? "已到最后一页" : "已到第一页");
            return;
        }
        int index = mChapterIndex + delta;
        if (!hasChapter(index)) {
            showHint(index < 0 ? "已是第一章" : "已是最后一章");
            return;
        }
        saveChapterProgress();
        int page = delta > 0 ? 0 : Integer.MAX_VALUE;
        float frac = delta > 0 ? 0f : -1f;
        Log.i(TAG, "advanceChapter " + mChapterIndex + " -> " + index);
        loadChapter(index, page, frac, null);
        showHint("第 " + (index + 1) + " / " + mChapterDirs.size() + " 章");
    }

    /** Authoritative page count: the downloader's real total when known,
     *  otherwise the number of files we can see on disk. */
    private int pageTotal() {
        return mPageTotal > mPages.size() ? mPageTotal : mPages.size();
    }

    private String counterText() {
        String base = (mIndex + 1) + " / " + pageTotal();
        if (mChapterDirs != null && mChapterDirs.size() > 1) {
            return "章" + (mChapterIndex + 1) + "/" + mChapterDirs.size() + " · " + base;
        }
        return base;
    }

    private void saveChapterProgress() {
        if (mChapterProgressKey == null || mFolder == null
                || !mSettings.bool(MangaSettings.PROGRESS_ENABLED)) {
            return;
        }
        float frac = 0f;
        if (isContinuous() && mStripView != null) {
            float height = mStripView.scaledHeight(2);
            frac = height > 0f ? mStripOffset / height : 0f;
        }
        progressPrefs().edit()
                .putString("chap:" + mChapterProgressKey, mChapterIndex + "|" + mIndex + "|" + frac)
                .apply();
    }

    /** Parses "chapter|page|frac" saved by saveChapterProgress(); null if absent. */
    public static float[] readChapterProgress(SharedPreferences prefs, String progressKey) {
        String raw = prefs.getString("chap:" + progressKey, null);
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split("\\|");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new float[]{Float.parseFloat(parts[0]), Float.parseFloat(parts[1]),
                    Float.parseFloat(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
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
        // Chapter-boundary fallback. Any path that asks for an out-of-range
        // page (thumbstick, auto-advance, seek bar, tap zone) must cross into
        // the neighbouring chapter instead of silently doing nothing.
        chapterLoadingGuard();
        int total = pageTotal();
        if (!mDestroyed && !mPages.isEmpty() && mChapterDirs != null
                && mChapterDirs.size() > 1 && !mChapterLoading) {
            if (index >= total && hasChapter(mChapterIndex + 1)) {
                Log.i(TAG, "showPage boundary -> next chapter (" + index + ")");
                advanceChapter(1);
                return;
            }
            if (index < 0 && hasChapter(mChapterIndex - 1)) {
                Log.i(TAG, "showPage boundary -> previous chapter (" + index + ")");
                advanceChapter(-1);
                return;
            }
        }
        if (mDestroyed || mPages.isEmpty() || index < 0 || index >= mPages.size()) {
            if (mPages.isEmpty()) {
                hideLoading();
                mPageCounter.setText("0 / 0");
                showHint("该目录没有可阅读的图片或视频");
            } else if (index >= mPages.size() && index < total) {
                // Page belongs to this chapter but has not landed yet.
                mPendingTargetPage = index;
                showHint("加载第 " + (index + 1) + " 张…");
            }
            return;
        }
        mPendingTargetPage = -1;
        stopVideo(true);
        cancelAuto();
        final int generation = ++mLoadGeneration;
        final int stripGeneration = ++mStripGeneration;
        mIndex = index;
        mRequestedPageKey = null;
        mVideoCompleted = false;
        mZoom = 1.0f;
        mPanX = 0f;
        mPanY = 0f;
        mScrollY = 0.0f;
        mStripOffset = 0.0f;
        mStripLastZoom = 1.0f;
        clearStripSegments();
        if (mSettings.bool(MangaSettings.PROGRESS_ENABLED)) {
            progressPrefs().edit().putInt(pageProgressKey(), mIndex).apply();
        }
        saveChapterProgress();

        final File page = mPages.get(mIndex);
        final boolean continuous = isContinuous();
        mTiledPage = false;
        mDisplayWidth = 0;
        mDisplayHeight = 0;
        if (mStripView != null) {
            // Clear BOTH drawing modes. Leaving tiles/strip behind made the
            // previous page (often the last continuous-mode page) flash for
            // one frame whenever the mode or image changed.
            mStripView.clearTiles();
            mStripView.setStrip(new Bitmap[5], 1.0f, 1.0f, 0.0f);
        }
        mTiledPage = false;
        applyDisplaySettings();
        mPageCounter.setText(counterText());
        mGotoDialog.setVisibility(GONE);
        notifyTrayProgress();
        preloadNext();

        if (MangaSettings.hasExtension(page.getName(), mSettings.videoExtensions())) {
            // Minimal, proven path (same as the build where video worked):
            // make the TextureView visible and let MediaPlayer attach to the
            // SurfaceTexture delivered by onSurfaceTextureAvailable().
            // No synthetic texture, no path tricks, no extra layout work.
            mPageImage.setVisibility(GONE);
            mVideoTexture.setVisibility(VISIBLE);
            setStripVisible(false);
            mVideoFitMode = 0;
            mVideoZoom = 1.0f;
            mVideoWidth = 0;
            mVideoHeight = 0;
            mVideoRect.setEmpty();
            mLastVideoTransformLog = null;
            mVideoPointerInWidget = false;
            showVideoBar(false);
            updateVideoControls();
            if (mVideoSeek != null) {
                mVideoSeek.setProgress(0);
                mVideoTime.setText("00:00 / 00:00");
            }
            if (mVideoProgressTrack != null) {
                mVideoProgressTrack.setVisibility(GONE);
                updateVideoProgressTrackFill();
            }
            if (mSettings.bool(MangaSettings.READER_VIDEO_AUTOPLAY)) {
                playVideo(page);
            } else {
                showHint("点击画面播放视频");
            }
            return;
        }

        showVideoBar(false);
        stopVideoTick();
        if (mVideoProgressTrack != null) {
            mVideoProgressTrack.setVisibility(GONE);
        }
        mVideoTexture.setVisibility(GONE);
        // An oversized page is shown as full-resolution tiles rather than one
        // downsampled bitmap, so zooming in never loses text detail.
        // Double-page spreads must go through decodeDisplay() (which merges
        // two pages). Tiling bypasses that entirely, which is why large pages
        // never showed as a spread. combineSideBySide caps at 4096 px so the
        // merged bitmap stays well under the Canvas draw limit.
        final boolean tiled = !continuous && !mDoublePage && needsTiling(page);
        mPageImage.setVisibility(continuous || tiled ? GONE : VISIBLE);
        setStripVisible(continuous || tiled);
        final String cacheKey = continuous ? stripKey(mIndex) : displayKey(mIndex);
        if (tiled) {
            // Deliberately not cached in the Bitmap LruCache: a set of tiles can
            // be several hundred MB and must not evict all the small pages.
            Bitmap preview = mCache.get(cacheKey);
            mCurrent = preview;
            mPageImage.setImageBitmap(preview);
            if (preview != null) {
                mDisplayWidth = preview.getWidth();
                mDisplayHeight = preview.getHeight();
                mPageImage.setVisibility(VISIBLE);
                setStripVisible(false);
                applyImageTransform();
            }
            mExecutor.execute(() -> {
                if (generation != mLoadGeneration || mDestroyed) {
                    return;
                }
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
                            mPageImage.setImageBitmap(safeForImageView(fallback));
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
                    mPageImage.setVisibility(GONE);
                    setStripVisible(true);
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
        Bitmap cached = continuous ? null : mCache.get(detailKey(mIndex));
        if (cached == null || cached.isRecycled()) {
            cached = mCache.get(cacheKey);
        }
        Log.i(TAG, "page cache " + (cached == null ? "miss" : "hit")
                + " index=" + mIndex + " pending=" + mPendingPages.size());
        if (cached != null && !cached.isRecycled()) {
            displayLoadedPage(cached);
            return;
        }
        mCurrent = null;
        if (!continuous) {
            mPageImage.setImageDrawable(null);
        }
        mRequestedPageKey = cacheKey;
    }

    private void displayLoadedPage(Bitmap bitmap) {
        mRequestedPageKey = null;
        hideLoading();
        mCurrent = bitmap;
        if (bitmap != null) {
            mDisplayWidth = bitmap.getWidth();
            mDisplayHeight = bitmap.getHeight();
            if (isContinuous()) {
                rebuildStrip(bitmap, mStripGeneration);
            } else {
                mPageImage.setImageBitmap(safeForImageView(bitmap));
                applyImageTransform();
                // FIRST-OPEN FIX: when a manga is opened for the very first time
                // the widget has just been created and shown; the async decode
                // can land before the ImageView has been measured, so
                // applyImageTransform() bails out on vw/vh==0 and the MATRIX
                // ImageView draws nothing (only the hint text is visible).
                // Re-apply on the next frame once layout has produced a real
                // size. Subsequent chapter/page turns are unaffected because
                // the view is already measured by then.
                mPageImage.post(this::applyImageTransform);
                restorePageDetail(bitmap);
            }
        } else {
            showHint("无法解码：" + mPages.get(mIndex).getName());
        }
        finishPageLoad(mLoadGeneration);
    }

    private void restorePageDetail(Bitmap preview) {
        if (pageCacheBudget() >= SINGLE_BITMAP_LIMIT) {
            return;
        }
        // Already showing the cached detail pass - nothing to upgrade.
        if (preview == mCache.get(detailKey(mIndex))) {
            return;
        }
        File first = mPages.get(mIndex);
        // AVIF: the preview already matches the window resolution; a second
        // full native decode (3+ s) only to swap bitmaps caused flicker and
        // its 54 MB result exceeded the per-page cache budget, so it was
        // evicted instantly and re-decoded on every revisit.
        if (MangaImageDecoder.isAvif(first)) {
            return;
        }
        File second = mDoublePage && mIndex + 1 < mPages.size() && !isVideoIndex(mIndex + 1)
                ? mPages.get(mIndex + 1) : null;
        int[] dimensions = MangaImageDecoder.cachedDimensions(first);
        if (second == null && (dimensions == null
                || (dimensions[0] <= preview.getWidth() && dimensions[1] <= preview.getHeight()))) {
            return;
        }
        final int generation = mLoadGeneration;
        final boolean crop = mSettings.bool(MangaSettings.READER_CROP_BORDERS);
        final String key = detailKey(mIndex);
        Bitmap cachedDetail = mCache.get(key);
        if (cachedDetail != null && !cachedDetail.isRecycled()) {
            // Revisits swap instantly instead of re-decoding for seconds; the
            // repeated preview->detail decode was the AVIF flicker source.
            final Bitmap detail = cachedDetail;
            mMainHandler.post(() -> applyPageDetail(detail, preview, generation));
            return;
        }
        mExecutor.execute(() -> {
            Bitmap detail = decodeDisplay(first, second, SINGLE_BITMAP_LIMIT, crop,
                    () -> mDestroyed || generation != mLoadGeneration);
            if (detail != null) {
                mCache.put(key, detail);
            }
            final Bitmap result = detail;
            mMainHandler.post(() -> applyPageDetail(result, preview, generation));
        });
    }

    private String detailKey(int index) {
        return displayKey(index) + ":detail";
    }

    private void applyPageDetail(Bitmap detail, Bitmap preview, int generation) {
        if (mDestroyed || generation != mLoadGeneration || mCurrent != preview
                || detail == null || detail.isRecycled()) {
            return;
        }
        // Keep the cached preview on screen until full detail is ready; never clear between them.
        mCurrent = detail;
        mDisplayWidth = detail.getWidth();
        mDisplayHeight = detail.getHeight();
        mPageImage.setImageBitmap(safeForImageView(detail));
        applyImageTransform();
        mPageImage.post(this::applyImageTransform);
        Log.i(TAG, "page detail index=" + mIndex + " size="
                + detail.getWidth() + "x" + detail.getHeight());
    }

    private void finishPageLoad(int generation) {
        applyTransition();
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

    /** Last line of defence: ImageView draws through RecordingCanvas, which
     *  crashes the whole process for any bitmap above ~100 MB. Never hand it
     *  such a bitmap - downsample just this once if a code path slipped
     *  through the tiling decision. Normal 1:1 pages arrive here well below
     *  the cap, so nothing is lost for regular reading. */
    private Bitmap safeForImageView(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return bitmap;
        }
        long bytes = bitmap.getAllocationByteCount();
        if (bytes <= SINGLE_BITMAP_LIMIT) {
            return bitmap;
        }
        double ratio = Math.sqrt((double) SINGLE_BITMAP_LIMIT / (double) bytes);
        int w = Math.max(1, (int) (bitmap.getWidth() * ratio));
        int h = Math.max(1, (int) (bitmap.getHeight() * ratio));
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, w, h, true);
        Log.w(TAG, "bitmap over draw limit, downsampled " + bitmap.getWidth() + "x"
                + bitmap.getHeight() + " -> " + w + "x" + h);
        return scaled != null ? scaled : bitmap;
    }

    /** Continuous mode keeps 5 pages decoded at once, so it needs a much
     *  smaller per-page budget than single-page viewing. */
    private static final long CONTINUOUS_PAGE_BYTES = 24L * 1024 * 1024;

    private Bitmap decodePage(File file) {
        return decodePage(file, SINGLE_BITMAP_LIMIT);
    }

    private Bitmap decodePage(File file, long byteBudget) {
        return decodePage(file, byteBudget, mSettings.bool(MangaSettings.READER_CROP_BORDERS));
    }

    private Bitmap decodePage(File file, long byteBudget, boolean crop) {
        try {
            if (MangaImageDecoder.isAvif(file)) {
                Bitmap bitmap = MangaImageDecoder.decodeAvifWithinBudget(file, byteBudget);
                return bitmap != null && crop ? cropBorders(bitmap) : bitmap;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            MangaImageDecoder.decodeBounds(file, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            // No resolution cap. Every page decodes at 1:1 native resolution;
            // the old 4096-edge / 150 MB downsampling was exactly what made
            // zoomed-in comic text look blurry. Only the continuous-mode path
            // (which needs one Bitmap per page) falls back to a sample when a
            // single bitmap would exceed Canvas' draw limit. Single-page mode
            // uses decodePageTiles() for those pages and stays at full res.
            // Bytes AND longest edge matter: a 3000x40000 strip can be under
            // the byte budget yet exceed the GPU texture height, in which case
            // BitmapFactory / Canvas silently fails and the page won't open.
            final int MAX_EDGE = 16384;
            int sample = 1;
            while (sample <= 32) {
                long w = (bounds.outWidth + sample - 1L) / sample;
                long h = (bounds.outHeight + sample - 1L) / sample;
                if (Math.max(w, h) <= MAX_EDGE && w * h * 4L <= byteBudget) {
                    break;
                }
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            // RGB_565 halves pixel memory/bandwidth vs ARGB_8888. On manga
            // (mostly greyscale line art) the difference is imperceptible, and
            // it roughly doubles the number of strip tiles that fit in cache -
            // the main cause of scroll stutter in long-strip mode.
            Bitmap bitmap = MangaImageDecoder.decodeSampled(file, sample,
                    Bitmap.Config.RGB_565);
            if (bitmap != null && crop) {
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
        // Android's region decoder cannot tile AVIF; never fully decode it on the UI thread.
        if (MangaImageDecoder.isAvif(file)) {
            return false;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            MangaImageDecoder.decodeBounds(file, bounds);
            long bytes = (long) bounds.outWidth * bounds.outHeight * 4L;
            // Anything that would exceed the Canvas DRAW limit must be tiled.
            // A 7008x4672 page (124.9 MB) used to fall through to a single
            // bitmap here because the threshold was 150 MB - and crashed.
            return bounds.outWidth > 0 && bounds.outHeight > 0
                    && bytes > SINGLE_BITMAP_LIMIT && bytes <= MAX_TILED_BYTES;
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
            if ((h + rows - 1) / rows > MAX_TILES) {
                decoder.recycle();
                return null; // too large to hold at 1:1; caller uses a sampled bitmap
            }
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

    private int preloadRadius() {
        int radius = Math.max(0, Math.min(4, mSettings.integer(MangaSettings.READER_PRELOAD)));
        return isContinuous() ? Math.max(2, radius) : radius;
    }

    private long pageCacheBudget() {
        long budget = (long) mCache.maxSize() * 1024 / (2 * preloadRadius() + 1);
        return Math.min(isContinuous() ? CONTINUOUS_PAGE_BYTES : SINGLE_BITMAP_LIMIT, budget);
    }

    private void preloadNext() {
        if (mDestroyed || mPages.isEmpty()) {
            return;
        }
        List<PageLoad> ordered = new ArrayList<>();
        mWindowKeys.clear();
        addWindowPage(mIndex, ordered);
        int direction = mSettings.integer(MangaSettings.READER_DIRECTION) == 1 ? -1 : 1;
        for (int distance = 1; distance <= preloadRadius(); distance++) {
            addWindowPage(mIndex + direction * distance, ordered);
        }
        for (int distance = 1; distance <= preloadRadius(); distance++) {
            addWindowPage(mIndex - direction * distance, ordered);
        }
        if (!isContinuous()) {
            // Keep the upgraded detail passes of the current neighbourhood so
            // paging back and forth never re-decodes (AVIF takes seconds).
            for (int i = mIndex - 1; i <= mIndex + 1; i++) {
                if (i >= 0 && i < mPages.size() && !isVideoIndex(i)) {
                    mWindowKeys.add(detailKey(i));
                }
            }
        }
        for (String key : new ArrayList<>(mPendingPages.keySet())) {
            if (!mWindowKeys.contains(key)) {
                mPendingPages.remove(key).cancelled = true;
            }
        }
        for (String key : mCache.snapshot().keySet()) {
            if (!mWindowKeys.contains(key)) {
                mCache.remove(key);
            }
        }
        mPreloadExecutor.getQueue().clear();
        mExecutor.getQueue().removeIf(task -> task instanceof PageLoad);
        for (PageLoad load : ordered) {
            if (!load.started) {
                if (load.index == mIndex) {
                    mExecutor.execute(load);
                } else {
                    mPreloadExecutor.execute(load);
                }
            }
        }
        Log.i(TAG, "preload window index=" + mIndex + " radius=" + preloadRadius()
                + " cached=" + mCache.snapshot().size() + " pending=" + mPendingPages.size());
        if (mChapterProvider != null && mChapterDirs != null && mChapterDirs.size() > 1) {
            int radius = Math.max(1, preloadRadius());
            if (mIndex + radius >= pageTotal() - 1 && hasChapter(mChapterIndex + 1)) {
                mChapterProvider.prefetchChapter(mChapterIndex + 1);
            }
            if (mIndex - radius <= 0 && hasChapter(mChapterIndex - 1)) {
                mChapterProvider.prefetchChapter(mChapterIndex - 1);
            }
        }
    }

    private void addWindowPage(int index, List<PageLoad> ordered) {
        if (index < 0 || index >= mPages.size() || isVideoIndex(index)) {
            return;
        }
        String key = isContinuous() ? stripKey(index) : displayKey(index);
        mWindowKeys.add(key);
        if (mCache.get(key) != null) {
            return;
        }
        PageLoad load = mPendingPages.get(key);
        if (load == null) {
            File second = mDoublePage && !isContinuous() && index + 1 < mPages.size()
                    && !isVideoIndex(index + 1) ? mPages.get(index + 1) : null;
            load = new PageLoad(key, index, mPages.get(index), second,
                    pageCacheBudget(), mSettings.bool(MangaSettings.READER_CROP_BORDERS));
            mPendingPages.put(key, load);
        }
        ordered.add(load);
    }

    private final class PageLoad implements Runnable {
        final String key;
        final int index;
        final File first;
        final File second;
        final long budget;
        final boolean crop;
        volatile boolean started;
        volatile boolean cancelled;

        PageLoad(String key, int index, File first, File second, long budget, boolean crop) {
            this.key = key;
            this.index = index;
            this.first = first;
            this.second = second;
            this.budget = budget;
            this.crop = crop;
        }

        @Override
        public void run() {
            synchronized (this) {
                if (started || cancelled) {
                    return;
                }
                started = true;
            }
            long start = android.os.SystemClock.uptimeMillis();
            MangaImageDecoder.setAvifPriority(index == mIndex);
            Bitmap bitmap;
            try {
                bitmap = decodeDisplay(first, second, budget, crop, () -> cancelled);
            } finally {
                MangaImageDecoder.setAvifPriority(false);
            }
            mMainHandler.post(() -> {
                if (mDestroyed || cancelled || mPendingPages.get(key) != this) {
                    return;
                }
                mPendingPages.remove(key);
                if (!mWindowKeys.contains(key)) {
                    return;
                }
                if (bitmap != null) {
                    mCache.put(key, bitmap);
                }
                Log.i(TAG, "page decoded index=" + index + " ms="
                        + (android.os.SystemClock.uptimeMillis() - start)
                        + " bytes=" + (bitmap == null ? 0 : bitmap.getAllocationByteCount()));
                if (key.equals(mRequestedPageKey)) {
                    displayLoadedPage(bitmap);
                } else if (isContinuous() && key.equals(stripKey(index))) {
                    int slot = index - mIndex + 2;
                    if (slot >= 0 && slot < mStripSegments.length) {
                        mStripSegments[slot] = bitmap;
                        updateStripTransform();
                    }
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
        return "strip:" + (mFolder == null ? "" : mFolder.getAbsolutePath()) + ":" + index
                + ":" + pageCacheBudget() + ":" + mSettings.bool(MangaSettings.READER_CROP_BORDERS);
    }

    private void setStripVisible(boolean visible) {
        if (mStripView == null) {
            return;
        }
        mStripView.setVisibility(visible ? VISIBLE : GONE);
        if (!visible) {
            mStripView.setStrip(new Bitmap[5], 1.0f, 1.0f, 0.0f);
            mStripView.clearTiles();
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
        boolean retry = false;
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
            retry |= !mPendingPages.containsKey(key);
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (retry && now - mLastStripRetry >= 1000L) {
            mLastStripRetry = now;
            preloadNext();
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
        // Only rescale the offset when the CANVAS width actually changed.
        // Adjacent comic pages have different intrinsic widths; recomputing on
        // every seam used to yank the content back (the "upward twitch").
        if (mLastStripViewWidth > 0f
                && Math.abs(vw - mLastStripViewWidth) > 0.5f) {
            mStripOffset *= vw / mLastStripViewWidth;
        }
        mLastStripViewWidth = vw;
        // Crossing a seam changes the CURRENT page, and the strip scale is
        // anchored on the current page's width. Keep the viewer at the same
        // CONTENT position: offset is in view pixels, so it must follow the
        // scale change. Without this, an up-crossing (offset ~ page height)
        // landed a fraction of a page away - the "jump to previous page top"
        // whenever neighbouring pages had different widths. Down-crossings
        // (offset ~ 0) were insensitive, which is why only up-scroll jumped.
        if (mLastStripSourceWidth > 0 && cur.getWidth() != mLastStripSourceWidth) {
            mStripOffset *= (float) mLastStripSourceWidth / cur.getWidth();
        }
        mLastStripSourceWidth = cur.getWidth();
        mStripScale = newScale;
        if (mStripLastZoom > 0f && Math.abs(mZoom - mStripLastZoom) > 0.0001f) {
            mStripOffset *= mZoom / mStripLastZoom;
        }
        mStripLastZoom = mZoom;
        if (mPendingStripFrac != 0f) {
            float pendingH = mStripView.scaledHeight(2);
            float pendingVh = mStripView.getHeight();
            if (pendingH > 0f) {
                mStripOffset = mPendingStripFrac < 0f
                        ? Math.max(0f, pendingH - pendingVh)
                        : Math.min(mPendingStripFrac * pendingH, Math.max(0f, pendingH - pendingVh));
                mPendingStripFrac = 0f;
            }
        }
        mStripView.setStrip(mStripSegments, mStripScale, mZoom, mStripOffset);
    }

    /** Scrolls the long strip. Positive delta moves down the comic (content
     *  moves up). When the current page leaves the viewport the array slides by
     *  one page: the very same bitmaps are reused, so the seam is invisible. */
    private void scrollContinuous(float delta) {
        if (!isContinuous() || mStripSegments[2] == null) {
            return;
        }
        // A single input frame must never jump over several pages. Very large
        // deltas (fast stick + high zoom) used to overshoot and bounce back,
        // which looked like random up-jumps. Cap each frame to 30% viewport.
        final float vh = mStripView.getHeight();
        final float maxStep = Math.max(48f, vh * 0.30f);
        mStripOffset += Math.max(-maxStep, Math.min(maxStep, delta));
        // At most one seam per frame: crossing several in one tick forces
        // repeated array shifts and visible jumps.
        for (int guard = 0; guard < 1; guard++) {
            float curH = mStripView.scaledHeight(2);
            if (curH <= 0f) {
                break;
            }
            if (mStripOffset >= curH) {
                if (mIndex + 1 >= pageTotal()) {
                    chapterLoadingGuard();
                    if (hasChapter(mChapterIndex + 1) && !mChapterLoading) {
                        advanceChapter(1);
                        return;
                    }
                    mStripOffset = Math.max(0f, curH - Math.max(0f, vh));
                    break;
                }
                if (mIndex + 1 >= mPages.size()) {
                    // Next page is downloading - hold at the seam, don't jump
                    // to the next chapter and don't bounce off the page bottom.
                    mStripOffset = curH;
                    loadStripNeighbours(mStripGeneration);
                    break;
                }
                if (isVideoIndex(mIndex + 1)) {
                    mStripOffset = Math.max(0f, curH - Math.max(0f, vh));
                    break;
                }
                // Never advance onto a neighbour that is still decoding. Hold
                // just past the bottom seam instead of clamping a whole viewport
                // back to the current page's bottom: clamping made a held stick
                // oscillate ("scrolls down then rolls back"). Once the neighbour
                // decodes the same delta crosses the seam smoothly.
                Bitmap next = mStripSegments[3];
                if (next == null || next.isRecycled()) {
                    mStripOffset = curH + 0.001f;
                    loadStripNeighbours(mStripGeneration);
                    break;
                }
                mStripOffset -= curH;
                shiftStrip(1);
            } else if (mStripOffset < 0f) {
                if (mIndex - 1 < 0) {
                    chapterLoadingGuard();
                    if (hasChapter(mChapterIndex - 1) && !mChapterLoading) {
                        advanceChapter(-1);
                        return;
                    }
                    mStripOffset = 0f;
                    break;
                }
                if (isVideoIndex(mIndex - 1)) {
                    mStripOffset = 0f;
                    break;
                }
                // Check BEFORE shifting: shifting first and then discovering
                // the page is undecoded used to snap the offset to zero and
                // yank the picture back to the top (the "upward jump").
                Bitmap prev = mStripSegments[1];
                if (prev == null || prev.isRecycled()) {
                    // Hold just above the seam, NOT at the current page's top:
                    // snapping to 0 made the next frame (once the neighbour
                    // decoded) cross the seam with a full frame of accumulated
                    // delta and land on the previous page's TOP - the reported
                    // up-scroll jump. Holding here keeps the view at the seam
                    // so the crossing lands on the previous page's tail.
                    mStripOffset = -0.001f;
                    Log.i(TAG, "continuous up-hold at seam index=" + mIndex
                            + " prev decoding");
                    loadStripNeighbours(mStripGeneration);
                    break;
                }
                float prevH = mStripView.scaledHeight(1);
                if (prevH > 0f) {
                    // After the shift the previous page IS the current page and
                    // the viewport sits near its BOTTOM: the offset must be a
                    // large POSITIVE value (prevH + offset). Clamping it to <= 0
                    // snapped every up-crossing to the previous page's TOP, and
                    // holding the stick chained one top-jump per page backwards.
                    mStripOffset = Math.max(0f, Math.min(prevH, mStripOffset + prevH));
                }
                shiftStrip(-1);
            }
        }
        mStripView.setOffset(mStripOffset);
        mMainHandler.removeCallbacks(mChapterSaveTick);
        mMainHandler.postDelayed(mChapterSaveTick, 500);
    }

    /** Thumbstick scrolling is expressed in CONTENT pixels so one notch always
     *  moves the same amount of comic (e.g. one line of dialogue) regardless of
     *  canvas or image zoom. scrollContinuous() itself takes VIEW pixels. */
    private void scrollContinuousStick(float deltaContent) {
        float scale = mStripScale * mZoom;
        if (scale <= 0.001f) {
            scale = 1f;
        }
        scrollContinuous(deltaContent * scale);
    }

    /** Same compensation for the non-strip vertical mode (mScrollY is in view
     *  pixels while the image is drawn through a Matrix). */
    private float imageScaleFactor() {
        if (mDisplayWidth <= 0 || mPageImage.getWidth() <= 0) {
            return 1f;
        }
        return Math.max(0.05f,
                (mPageImage.getWidth() / (float) mDisplayWidth) * mZoom);
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
        mPageCounter.setText(counterText());
        notifyTrayProgress();
        if (mSettings.bool(MangaSettings.PROGRESS_ENABLED)) {
            progressPrefs().edit().putInt(pageProgressKey(), mIndex).apply();
        }
        saveChapterProgress();
        applyDisplaySettings();
        // Recompute the width-anchored scale NOW, together with the matching
        // offset rescale in updateStripTransform(). Keeping the old scale here
        // left the new current page drawn at the wrong width until a neighbour
        // decode happened to trigger updateStripTransform() seconds later -
        // the visible jump when up-scrolling across pages of different widths.
        updateStripTransform();
        preloadNext();
        loadStripNeighbours(mStripGeneration);
        Log.i(TAG, "continuous shift to " + (mIndex + 1) + "/" + mPages.size()
                + " offset=" + mStripOffset + " scale=" + mStripScale
                + " curW=" + (mStripSegments[2] != null ? mStripSegments[2].getWidth() : 0));
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
        // Pan a zoomed picture, clamped so the image can never be dragged
        // completely off screen (max pan = half the overflow on each side).
        float maxPanX = Math.max(0f, (bw * sx - vw) / 2.0f);
        float maxPanY = Math.max(0f, (bh * sy - vh) / 2.0f);
        mPanX = Math.max(-maxPanX, Math.min(maxPanX, mPanX));
        mPanY = Math.max(-maxPanY, Math.min(maxPanY, mPanY));
        dx += mPanX;
        dy += mPanY;
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
        if (mIndex + 1 < pageTotal()) {
            requestPage(mIndex + 1);
            if (mIndex + 1 >= mPages.size()) {
                // Target still downloading; wait for it instead of falling
                // through to the next chapter or declaring "last page".
                return;
            }
        } else if (mChapterDirs != null && mChapterIndex + 1 < mChapterDirs.size()) {
            advanceChapter(1);
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
        int target = mIndex + delta;
        chapterLoadingGuard();
        Log.i(TAG, "pageDelta target=" + target + " pages=" + mPages.size()
                + " chapters=" + (mChapterDirs == null ? 0 : mChapterDirs.size())
                + " chapterIndex=" + mChapterIndex + " loading=" + mChapterLoading);
        int total = pageTotal();
        if (mChapterDirs != null && mChapterDirs.size() > 1) {
            // Only cross chapters at the REAL end of the chapter. A page that
            // is part of this chapter but still downloading must be waited for,
            // not mistaken for the chapter boundary (the old bug: partial
            // folder of 8 -> turning past page 8 jumped to the next chapter).
            if (target >= total) {
                Log.i(TAG, "pageDelta -> next chapter");
                advanceChapter(1);
                return;
            }
            if (target < 0) {
                Log.i(TAG, "pageDelta -> previous chapter");
                advanceChapter(-1);
                return;
            }
        }
        requestPage(target);
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
        mStripOffset = 0.0f;
        if (value == 2 && mDoublePage) {
            mDoublePage = false;
            mSettings.put(MangaSettings.READER_DOUBLE_PAGE, false);
        }
        // Continuous mode has its own canvas aspect (1:1 by default).
        reapplyCanvasSize();
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

    /** Jump straight to a 0-based page, used by the tray scrubber. */
    public void jumpToPage(int index) {
        if (mPages.isEmpty()) {
            return;
        }
        requestPage(index);
    }

    /** Shows [index] if it is already on disk. If it belongs to the chapter but
     *  has not downloaded yet, remember it and wait for that page to land
     *  instead of mistaking the partial folder for a chapter boundary. */
    private void requestPage(int index) {
        int total = pageTotal();
        if (index < 0 || index >= total) {
            return;
        }
        if (index < mPages.size()) {
            mPendingTargetPage = -1;
            showPage(index);
        } else {
            mPendingTargetPage = index;
            showHint("加载第 " + (index + 1) + " 张…");
            // Make sure a download is actually running for the current chapter.
            chapterLoadingGuard();
        }
    }

    private void notifyTrayProgress() {
        Context context = getContext();
        if (!(context instanceof VRBrowserActivity)) {
            return;
        }
        TrayWidget tray = ((VRBrowserActivity) context).getTray();
        if (tray == null) {
            return;
        }
        int chapterCount = mChapterDirs == null ? 1 : mChapterDirs.size();
        tray.updateReaderProgress(mIndex, pageTotal(),
                mChapterIndex, chapterCount);
    }

    public void openGotoDialog() {
        if (mPages.isEmpty()) {
            return;
        }
        mGotoDialog.setVisibility(VISIBLE);
        mGotoInput.setText(String.valueOf(mIndex + 1));
        mGotoInput.setHint("1 - " + pageTotal());
        mGotoInput.requestFocus();
        mGotoInput.selectAll();
    }

    private void confirmGoto() {
        try {
            int page = Integer.parseInt(mGotoInput.getText().toString().trim());
            if (page >= 1 && page <= pageTotal()) {
                mGotoDialog.setVisibility(GONE);
                requestPage(page - 1);
            } else {
                Toast.makeText(getContext(), "页码范围 1 - " + pageTotal(),
                        Toast.LENGTH_SHORT).show();
            }
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), "请输入页码", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleTap(float x) {
        if (isVideoPage()) {
            toggleVideoPlay();
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
            return mVideoPrepared && mMediaPlayer != null && mMediaPlayer.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    // ----------------------------------------------------- double page / joystick

    /** Toggles two-up reading. Double-page spreads and the vertical long strip
     *  are mutually exclusive: enabling it leaves continuous mode and starts
     *  again in the default left-to-right direction. */
    public void toggleDoublePage() {
        mDoublePage = !mDoublePage;
        mSettings.put(MangaSettings.READER_DOUBLE_PAGE, mDoublePage);
        boolean leftContinuous = false;
        if (mDoublePage && mSettings.integer(MangaSettings.READER_DIRECTION) == 2) {
            mSettings.put(MangaSettings.READER_DIRECTION, 0);
            mScrollY = 0f;
            mStripOffset = 0f;
            clearStripSegments();
            mStripView.clearTiles();
            leftContinuous = true;
            showHint("双页：开（已退出连续模式，从左到右）");
        } else {
            showHint(mDoublePage ? "双页：开" : "双页：关");
        }
        // Rebuilding the SurfaceTexture causes a visible flash. Only do it when
        // the reading MODE actually changed (and with it the canvas aspect).
        if (leftContinuous) {
            reapplyCanvasSize();
        }
        if (!mPages.isEmpty()) {
            showPage(mIndex);
        }
    }

    // --------------------------------------------------- external control API

    /** Called from the Activity with the VR pointer position in widget
     *  coordinates. Used as the anchor for pointer-centred image zoom. */
    public void onPointerPosition(float x, float y) {
        mPointerX = x;
        mPointerY = y;
        // Activity forwards other widgets' coordinates too; dispatch establishes pointer ownership.
        if (mVideoPointerInWidget) {
            updateVideoPointer(x - getBorderWidth(), y - getBorderWidth());
        }
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_HOVER_EXIT) {
            mVideoPointerInWidget = false;
            mVideoPointerX = mVideoPointerY = -1f;
            // Delay hiding: a controller ray crossing between the video and the
            // bar (or flicking past) used to hide the bar instantly, making the
            // seek bar impossible to click. Keep it up for a full second.
            scheduleVideoBarHide();
        } else {
            mVideoPointerInWidget = true;
            // Show immediately - the hide delay must not slow down reveal.
            mMainHandler.removeCallbacks(mHideVideoBarRunnable);
            updateVideoPointer(event.getX(), event.getY());
        }
        // Observe the widget's event, not child hover exits when crossing buttons.
        return super.dispatchHoverEvent(event);
    }

    private void scheduleVideoBarHide() {
        mMainHandler.removeCallbacks(mHideVideoBarRunnable);
        // While the user is dragging the seek bar, never auto-hide.
        if (mVideoSeekDragging) {
            return;
        }
        mMainHandler.postDelayed(mHideVideoBarRunnable, VIDEO_BAR_HIDE_DELAY_MS);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        mVideoPointerInWidget = event.getActionMasked() != MotionEvent.ACTION_CANCEL;
        updateVideoPointer(event.getX(), event.getY());
        boolean handled = super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            mVideoSeekDragging = false;
            updateVideoBarVisibility();
        }
        return handled;
    }

    private void updateVideoPointer(float x, float y) {
        if (mStage == null) {
            return;
        }
        mVideoStageOffset.set(0, 0, 0, 0);
        offsetDescendantRectToMyCoords(mStage, mVideoStageOffset);
        mVideoPointerX = x - mVideoStageOffset.left;
        mVideoPointerY = y - mVideoStageOffset.top;
        updateVideoBarVisibility();
    }

    private void updateVideoBarVisibility() {
        if (mVideoBar == null) {
            return;
        }
        boolean inside = mVideoPointerInWidget
                && mVideoRect.contains(mVideoPointerX, mVideoPointerY);
        boolean atBottom = inside && mVideoPointerY >= mVideoRect.bottom - dp(32);
        FrameLayout.LayoutParams barLp = (FrameLayout.LayoutParams) mVideoBar.getLayoutParams();
        boolean overBar = inside && mVideoBar.getVisibility() == VISIBLE
                && mVideoPointerY >= barLp.topMargin;
        boolean shouldShow = isVideoPage() && !mVideoRect.isEmpty()
                && (mVideoSeekDragging || atBottom || overBar);
        if (shouldShow) {
            // Reveal at once and cancel any pending hide.
            mMainHandler.removeCallbacks(mHideVideoBarRunnable);
            showVideoBar(true);
        } else {
            // Do not yank the bar away mid-interaction; let the delayed hide
            // run so the pointer can still reach the seek bar / buttons.
            scheduleVideoBarHide();
        }
    }

    /** TEMPORARY image zoom: scales only the picture content inside a
     *  fixed-size window. showPage() resets mZoom, so the next page is 100%. */
    public void zoomBy(float factor) {
        zoomByAt(factor, mPointerX, mPointerY);
    }

    /** Zoom with an optional anchor. When the VR pointer is inside the
     *  picture, the comic content under the pointer stays fixed (zoom toward
     *  what you are looking at). When it is outside (or unset), fall back to
     *  the original centred zoom. */
    private void zoomByAt(float factor, float anchorX, float anchorY) {
        float vw = (mStage != null && mStage.getWidth() > 0)
                ? mStage.getWidth() : mPageImage.getWidth();
        float vh = (mStage != null && mStage.getHeight() > 0)
                ? mStage.getHeight() : mPageImage.getHeight();
        // The Activity forwards pointer coordinates in WIDGET space. Subtract
        // the border that MotionEventGenerator adds so they match stage space.
        int border = getBorderWidth();
        float px = anchorX - border;
        float py = anchorY - border;
        boolean inside = vw > 0 && vh > 0 && px >= 0 && px <= vw
                && py >= 0 && py <= vh;
        float oldZoom = mZoom;
        float newZoom = clamp(oldZoom * factor, 0.2f, 8.0f);
        if (inside && oldZoom > 0f) {
            // Keep the image point under the pointer fixed. Only the zoom
            // RATIO matters here (the base fit scale cancels out):
            //   newPan = pan + (pointer - centre - pan) * (1 - newZoom/oldZoom)
            float cx = vw / 2f;
            float cy = vh / 2f;
            float ratio = 1f - newZoom / oldZoom;
            mPanX += (px - cx - mPanX) * ratio;
            mPanY += (py - cy - mPanY) * ratio;
            Log.d(TAG, "anchor zoom pointer=" + px + "," + py
                    + " zoom " + oldZoom + "->" + newZoom + " pan=" + mPanX + "," + mPanY);
        } else if (anchorX >= 0) {
            Log.d(TAG, "anchor zoom OUTSIDE pointer=" + px + "," + py
                    + " stage=" + vw + "x" + vh);
        }
        mZoom = newZoom;
        markZoomGesture();
        applyImageTransform();
        showZoomHint("图片缩放：" + Math.round(mZoom * 100) + "%（切页重置）");
    }

    /** Hint text changes force a full widget redraw. Throttle it so a stream
     *  of zoom steps cannot make the picture flicker. */
    private void showZoomHint(String text) {
        long now = System.currentTimeMillis();
        if (now - mLastZoomHint >= 250L) {
            mLastZoomHint = now;
            showHint(text);
        }
    }

    /** Coalesce all resize requests from one frame into a single placement
     *  update. post() applies on the very next frame (visibly immediate), while
     *  a delayed merge made zoom appear to "not take effect" until reopening. */
    private void requestUserScale() {
        if (mScaleUpdatePending) {
            return;
        }
        mScaleUpdatePending = true;
        // Coalesce to ~11 updates/second. Rebuilding the SurfaceTexture on
        // every 50 ms tick is what produced the visible flicker; this keeps
        // zoom responsive while cutting rebuilds by more than half. The
        // final value is always persisted by applyUserScale().
        mMainHandler.postDelayed(mScaleUpdate, 90L);
    }

    /** Zoom rate grows with stick deflection: a light nudge keeps fine
     *  control, a full push zooms about six times faster. */
    private static float zoomRateForMagnitude(float magnitude) {
        float m = Math.min(1.0f, Math.abs(magnitude));
        return 1.0f + 5.0f * m;
    }

    /** PERSISTENT canvas zoom: resizes the widget itself (worldWidth / width /
     *  height). Saved to prefs, so it survives page changes and restarts. */
    public void scaleCanvasBy(float factor) {
        markZoomGesture();
        setActiveScale(clamp(activeScale() * factor, 0.4f, 3.0f));
        applyUserScale();
        showZoomHint("画布大小：" + Math.round(activeScale() * 100) + "%（"
                + (isContinuous() ? "连续" : "单/双页") + "，持久）");
    }

    /** Re-apply the default placement, window size and image zoom. */
    public void resetPosition() {
        setActiveScale(1.0f);
        mSettings.put(activeScaleKey(), 1.0f);
        mZoom = 1.0f;
        mPanX = 0f;
        mPanY = 0f;
        mScrollY = 0.0f;
        mStripOffset = 0.0f;
        mStripLastZoom = 1.0f;
        Context context = getContext();
        int windowWidth = SettingsStore.getInstance(context).getWindowWidth();
        int windowHeight = SettingsStore.getInstance(context).getWindowHeight();
        WidgetPlacement p = getPlacement();
        float previousY = p.translationY;
        float previousZ = p.translationZ;
        p.worldWidth = WidgetPlacement.floatDimension(context, R.dimen.window_world_width)
                * (float) windowWidth / (float) SettingsStore.WINDOW_WIDTH_DEFAULT * 0.60f;
        int netWidth = (int) (windowWidth * 0.78f);
        p.width = netWidth + mBorderWidth * 2;
        p.height = canvasHeight(netWidth, windowHeight, 1.0f);
        float readerWorldHeight = p.worldWidth
                * ((float) p.height / (float) p.width) * 360.0f;
        float trayY = WidgetPlacement.unitFromMeters(context, R.dimen.tray_world_y);
        p.translationY = (trayY + 60.0f) + readerWorldHeight * 0.5f;
        p.translationZ = WidgetPlacement.getWindowWorldZMeters(context) + 220.0f;
        mWidgetManager.updateWidget(this);
        notifyCanvasResized(p.translationY - previousY, p.translationZ - previousZ);
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
        // MUST include the folder: s:0 / d:0 are reused by every manga in the
        // shelf, so opening a different folder showed the previous manga's
        // page. Include the file name too so edits/reordering cannot collide.
        String path = mFolder == null ? "" : mFolder.getAbsolutePath();
        String name = mPages != null && index >= 0 && index < mPages.size()
                ? mPages.get(index).getAbsolutePath() : "";
        return (mDoublePage ? "d:" : "s:") + path + ":" + index + ":" + name
                + ":" + pageCacheBudget() + ":" + mSettings.bool(MangaSettings.READER_CROP_BORDERS);
    }

    private Bitmap decodeDisplay(File first, File second, long budget, boolean crop,
                                 java.util.function.BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            return null;
        }
        Bitmap a = decodePage(first, budget, crop);
        if (a == null || second == null || cancelled.getAsBoolean()) {
            return a;
        }
        Bitmap b = decodePage(second, budget, crop);
        if (b == null) {
            return a;
        }
        if (a.getHeight() >= a.getWidth() && b.getHeight() >= b.getWidth()) {
            Bitmap combined = combineSideBySide(a, b, budget);
            if (combined != a) {
                a.recycle();
            }
            b.recycle();
            return combined;
        }
        b.recycle();
        return a;
    }

    private Bitmap combineSideBySide(Bitmap a, Bitmap b, long budget) {
        try {
            int h = Math.max(a.getHeight(), b.getHeight());
            int wa = Math.round(a.getWidth() * ((float) h / a.getHeight()));
            int wb = Math.round(b.getWidth() * ((float) h / b.getHeight()));
            // Keep the combined spread within a safe texture ceiling. Two
            // full-resolution pages can otherwise add up to a 300+ MB bitmap
            // and throw OOM, which showed up as a brief zoom flash followed by
            // a silent fallback to a single page (or a page that won't open).
            final int MAX = 4096;
            int totalW = wa + wb;
            if (h > MAX || totalW > MAX || (long) totalW * h * 4L > budget) {
                float scale = Math.min(Math.min((float) MAX / h, (float) MAX / totalW),
                        (float) Math.sqrt((double) budget / ((long) totalW * h * 4L)));
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
        // WebKit's ScaleScrollDelta shrinks stick deltas to as little as 0.15x
        // for the first ~2 s of a gesture. In the browser that is fine, but in
        // the reader it made paging and canvas zoom feel dead unless the
        // pointer happened to hit the widget (which uses the unscaled axes).
        // Restore the true stick amplitude so controls work everywhere.
        final float gain = 4.0f;
        final float rawDy = dy; // unscaled amplitude, used for variable-speed zoom
        dx *= gain;
        dy *= gain;
        mScrollAccumX += dx;
        mScrollAccumY += dy;

        // On a video page the horizontal stick seeks +/-5 s instead of paging.
        if (isVideoPage() && !mResizeModifier) {
            if (!mJoyPageCooldown && Math.abs(mScrollAccumX) > 0.22f) {
                // Keep the same sign convention as image paging
                // (pageDelta(mScrollAccumX > 0 ? -1 : 1)): pushing right must
                // advance the video, not rewind it.
                seekVideo(mScrollAccumX > 0 ? VIDEO_SEEK_STEP_MS : -VIDEO_SEEK_STEP_MS);
                mScrollAccumX = 0f;
                mJoyPageCooldown = true;
                mMainHandler.postDelayed(() -> mJoyPageCooldown = false, 350);
            }
            mScrollAccumY = 0f;
            mMainHandler.removeCallbacks(mScrollDecay);
            mMainHandler.postDelayed(mScrollDecay, 220);
            return;
        }

        // While a resize / zoom gesture is active the stick's unavoidable
        // horizontal component must NOT be interpreted as a page turn.
        boolean pagingSuspended = mResizeModifier || zoomGestureActive();
        if (mResizeModifier) {
            markZoomGesture();
            // NEW MAPPING (per user): with the side button held, LEFT/RIGHT
            // resizes the CANVAS, UP/DOWN scales the PICTURE. Previously both
            // axes zoomed the canvas, and bare up/down also zoomed the picture,
            // which was easy to trigger by accident and conflicted with the
            // continuous-mode scroll habit.
            if (Math.abs(dx) > Math.abs(dy)) {
                float rate = 0.012f * zoomRateForMagnitude(dx / gain);
                setActiveScale(clamp(activeScale() * (1.0f - dx * rate), 0.4f, 3.0f));
                requestUserScale();
            } else {
                float rate = 0.010f * zoomRateForMagnitude(rawDy);
                zoomByAt(1.0f - dy * rate, mPointerX, mPointerY);
            }
        } else {
            int direction = mSettings.integer(MangaSettings.READER_DIRECTION);
            if (direction == 2) {
                // Seamless long-strip scroll: dy > 0 scrolls down into the
                // next page, dy < 0 scrolls up into the previous page's tail.
                if (isContinuous()) {
                    scrollContinuousStick(-dy * 14.0f * scrollSpeed());
                } else {
                    mScrollY -= dy * 14.0f * scrollSpeed() * imageScaleFactor();
                    applyImageTransform();
                }
            }
            // Bare up/down no longer zooms the picture: it was too easy to
            // trigger and conflicting with continuous-mode scrolling.
            mScrollAccumY = 0f;
        }

        if (pagingSuspended) {
            // Discard the accumulated horizontal drift instead of letting it
            // fire as soon as the modifier is released.
            mScrollAccumX = 0f;
            mMainHandler.removeCallbacks(mScrollDecay);
            mMainHandler.postDelayed(mScrollDecay, 220);
            return;
        }
        // In seamless long-strip mode a diagonal stick gesture has an
        // unavoidable horizontal component. Treating it as a page turn calls
        // showPage(), which resets the strip and snaps scroll back to the top
        // of a page - the reported "scrolls down but jumps back a few pages".
        // Continuous navigation is vertical-only, so discard horizontal drift.
        if (isContinuous()) {
            mScrollAccumX = 0f;
        } else if (!mJoyPageCooldown && Math.abs(mScrollAccumX) > 0.22f) {
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
        if (isVideoPage() && !mResizeModifier) {
            if (Math.abs(mAxisX) > dead && !mJoyPageCooldown) {
                seekVideo(mAxisX > 0 ? VIDEO_SEEK_STEP_MS : -VIDEO_SEEK_STEP_MS);
                mJoyPageCooldown = true;
                mMainHandler.postDelayed(() -> mJoyPageCooldown = false, 350);
            }
            mMainHandler.postDelayed(mJoyTick, 120);
            return;
        }
        if (mResizeModifier) {
            // Side button + LEFT/RIGHT = canvas zoom; UP/DOWN = picture zoom.
            markZoomGesture();
            if (Math.abs(mAxisX) > Math.abs(mAxisY)) {
                float rate = 0.05f * zoomRateForMagnitude(mAxisX);
                setActiveScale(clamp(activeScale() * (1.0f - mAxisX * rate), 0.4f, 3.0f));
                requestUserScale();
            } else {
                float rate = 0.04f * zoomRateForMagnitude(mAxisY);
                zoomByAt(1.0f - mAxisY * rate, mPointerX, mPointerY);
            }
        } else {
            int direction = mSettings.integer(MangaSettings.READER_DIRECTION);
            if (direction == 2) {
                // Vertical continuous scroll, like swiping a long image.
                if (isContinuous()) {
                    scrollContinuousStick(-mAxisY * 50.0f * scrollSpeed());
                } else {
                    mScrollY -= mAxisY * 50.0f * scrollSpeed() * imageScaleFactor();
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

    /** One zoom record for single/double page (and video), a completely
     *  independent one for continuous mode - the two canvases have different
     *  aspect ratios, so a shared multiplier never fits both. */
    private boolean useContinuousScale() {
        // Video shares the single/double-page record even if the user happens
        // to open it from continuous mode.
        return isContinuous() && !isVideoPage();
    }

    private float activeScale() {
        return useContinuousScale() ? mContinuousScale : mUserScale;
    }

    private void setActiveScale(float value) {
        if (useContinuousScale()) {
            mContinuousScale = value;
        } else {
            mUserScale = value;
        }
    }

    private String activeScaleKey() {
        return useContinuousScale() ? MangaSettings.READER_CONTINUOUS_SCALE
                : MangaSettings.READER_CANVAS_SCALE;
    }

    private void applyUserScale() {
        WidgetPlacement p = getPlacement();
        Context context = getContext();
        int windowWidth = SettingsStore.getInstance(context).getWindowWidth();
        int windowHeight = SettingsStore.getInstance(context).getWindowHeight();
        float baseWorld = WidgetPlacement.floatDimension(context, R.dimen.window_world_width)
                * (float) windowWidth / (float) SettingsStore.WINDOW_WIDTH_DEFAULT * 0.60f;

        // Single point of persistence: buttons, thumbstick and startup restore
        // all pass through here, so each mode's size survives a restart.
        final float scale = activeScale();
        mSettings.put(activeScaleKey(), scale);

        // Remember the CURRENT bottom edge (which may have been moved by a
        // drag) and keep it pinned: zooming grows the canvas upwards only, so
        // it can never slide down behind the grab bar / control bar.
        float previousY = p.translationY;
        float previousZ = p.translationZ;
        float previousHeight = p.worldWidth
                * ((float) p.height / (float) Math.max(1, p.width)) * 360.0f;
        float bottomY = previousY - previousHeight * 0.5f;

        int netWidth = (int) (windowWidth * 0.78f * scale);
        p.worldWidth = baseWorld * scale;
        p.width = netWidth + mBorderWidth * 2;
        p.height = canvasHeight(netWidth, windowHeight, scale);
        float readerWorldHeight = p.worldWidth
                * ((float) p.height / (float) p.width) * 360.0f;
        p.translationY = bottomY + readerWorldHeight * 0.5f;
        mWidgetManager.updateWidget(this);
        notifyCanvasResized(p.translationY - previousY, p.translationZ - previousZ);
    }

    /** Continuous (webtoon) mode uses a configurable canvas aspect - 1:1 by
     *  default - instead of the browser window's 16:9, which made comic pages
     *  look wider than tall. Single/double page modes keep the stock ratio. */
    private int canvasHeight(int netWidth, int windowHeight, float scale) {
        if (isContinuous()) {
            float aspect = MangaSettings.aspectRatio(clampAspect(
                    mSettings.integer(MangaSettings.READER_CONTINUOUS_ASPECT)));
            return Math.round(netWidth / aspect) + mBorderWidth * 2;
        }
        return (int) (windowHeight * 0.82f * scale) + mBorderWidth * 2;
    }

    private static int clampAspect(int index) {
        return Math.max(0, Math.min(MangaSettings.ASPECT_LABELS.length - 1, index));
    }

    /** Lets the settings panel apply a newly chosen aspect immediately. */
    public void reapplyCanvasSize() {
        if (!mDestroyed) {
            applyUserScale();
        }
    }

    /** The tray and the floating grab bar are children of this surface. When
     *  its centre moves during a canvas zoom, their relative translation must
     *  be corrected by the same amount in BOTH axes so they stay put on screen.
     *  A Z change is what made the control bar look bigger/smaller before. */
    private void notifyCanvasResized(float deltaParentY, float deltaParentZ) {
        // Always notify, even for a zero delta: the tray/bar dimensions are
        // re-pinned on every call, which is what keeps the control bar fixed.
        Context context = getContext();
        if (context instanceof VRBrowserActivity) {
            ((VRBrowserActivity) context).onMangaCanvasResized(deltaParentY, deltaParentZ);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public void persistState() {
        MediaPlayer player = mMediaPlayer;
        if (mVideoPrepared && player != null && !mVideoCompleted
                && mSettings.bool(MangaSettings.PROGRESS_ENABLED)
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
        saveChapterProgress();
        Log.i(TAG, "persistState index=" + mIndex);
    }

    /** Shows the centered loading overlay. [done]/[total] are page counts;
     *  [bytesDone]/[bytesTotal] are optional byte counts (>=0) used to compute
     *  a live download speed. Call on the main thread. */
    private void showLoading(String label, int done, int total,
                             long bytesDone, long bytesTotal) {
        if (mLoadingOverlay == null) {
            return;
        }
        mLoadingLabel.setText(label);
        if (mLoadingBar != null) {
            if (total > 0) {
                mLoadingBar.setIndeterminate(false);
                mLoadingBar.setMax(total);
                mLoadingBar.setProgress(Math.max(0, Math.min(done, total)));
            } else {
                mLoadingBar.setIndeterminate(true);
            }
        }
        if (bytesDone >= 0 && bytesTotal >= 0) {
            long elapsed = System.currentTimeMillis() - mLoadBytesStartMs;
            String speed = "";
            if (elapsed > 250 && bytesDone > 0) {
                double kbps = (bytesDone * 1000.0 / elapsed) / 1024.0;
                speed = kbps >= 1024
                        ? String.format(java.util.Locale.ROOT, "%.2f MB/s", kbps / 1024.0)
                        : String.format(java.util.Locale.ROOT, "%.0f KB/s", kbps);
            }
            long pct = bytesTotal > 0 ? (bytesDone * 100 / bytesTotal) : -1;
            mLoadingSpeed.setText(formatMiB(bytesDone) + " / " + formatMiB(bytesTotal)
                    + (pct >= 0 ? "  (" + pct + "%)" : "")
                    + (speed.isEmpty() ? "" : "  ·  " + speed));
        } else if (total > 0) {
            mLoadingSpeed.setText(done + " / " + total + " 页");
        } else {
            mLoadingSpeed.setText("");
        }
        mLoadingOverlay.setVisibility(VISIBLE);
    }

    private void hideLoading() {
        if (mLoadingOverlay != null) {
            mLoadingOverlay.setVisibility(GONE);
        }
    }

    private static String formatMiB(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Called from chapter onProgress (main thread). Shows/updates the centered
     *  download overlay while the chapter's first page is still landing. */
    private void updateChapterDownloadProgress(File dir, int done, int total) {
        if (mLoadBytesStartMs <= 0L) {
            mLoadBytesStartMs = System.currentTimeMillis();
        }
        long landed = dirBytes(dir);
        long estTotal = done > 0 ? (landed * total / done) : -1;
        if (done >= total && total > 0) {
            estTotal = landed;
        }
        showLoading("正在加载 " + (mChapterIndex + 1) + " / "
                        + (mChapterDirs == null ? 1 : mChapterDirs.size()) + " 章",
                done, total, landed, estTotal);
    }

    /** Begin a fresh loading cycle: reset speed baseline and show overlay. */
    private void startChapterLoading(String label) {
        mLoadBytesStartMs = System.currentTimeMillis();
        mLoadBytesDone = 0L;
        mLoadBytesTotal = -1L;
        showLoading(label, -1, -1, -1, -1);
    }

    /** Sums the byte size of all landed (non-.tmp) media files in a chapter
     *  cache dir - used to derive a live download speed without changing the
     *  downloader's progress interface. */
    private long dirBytes(File dir) {
        if (dir == null || !dir.isDirectory()) return 0L;
        File[] files = dir.listFiles();
        if (files == null) return 0L;
        long n = 0L;
        for (File f : files) {
            if (f != null && f.isFile() && !f.getName().endsWith(".tmp")) {
                n += f.length();
            }
        }
        return n;
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

    /** Controls overlay the video's bottom; they never reserve canvas space. */
    private static final int VIDEO_SEEK_STEP_MS = 5000;
    private static final String[] VIDEO_FIT_LABELS = {
            "等比适应屏幕", "等比适应高度", "等比适应宽度", "原始比例(不放大)"};

    private void setupVideoBar() {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xE6202028);
        bar.setPadding(dp(4), dp(2), dp(4), dp(2));
        bar.setClickable(true);
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            if (isVideoPage()) {
                layoutVideoOverlays();
            }
        });

        LinearLayout row1 = new LinearLayout(getContext());
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout row2 = new LinearLayout(getContext());
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bar.addView(row1, rowLp);

        mVideoPlayButton = videoButton("▶ 播放");
        mVideoPlayButton.setOnClickListener(v -> toggleVideoPlay());
        row1.addView(mVideoPlayButton);

        Button back = videoButton("« 5秒");
        back.setOnClickListener(v -> seekVideo(-VIDEO_SEEK_STEP_MS));
        row1.addView(back);

        Button forward = videoButton("5秒 »");
        forward.setOnClickListener(v -> seekVideo(VIDEO_SEEK_STEP_MS));
        row1.addView(forward);

        mVideoTime = new TextView(getContext());
        mVideoTime.setText("00:00 / 00:00");
        mVideoTime.setTextColor(0xFFE0E0E8);
        mVideoTime.setTextSize(15f);
        mVideoTime.setGravity(Gravity.CENTER);
        bar.addView(mVideoTime, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mVideoSeek = new SeekBar(getContext());
        mVideoSeek.setMax(1000);
        mVideoSeek.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        mVideoSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                MediaPlayer player = mMediaPlayer;
                if (mVideoPrepared && player != null) {
                    try {
                        mVideoCompleted = false;
                        int target = (int) (player.getDuration() * (long) progress / 1000L);
                        mVideoSeekGeneration++;
                        mVideoSeekTargetMs = target;
                        mVideoSeeking = true;
                        mVideoSeekStartedAt = android.os.SystemClock.uptimeMillis();
                        player.seekTo(target);
                    } catch (Exception ignored) {
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mVideoSeekDragging = true;
                mMainHandler.removeCallbacks(mHideVideoBarRunnable);
                showVideoBar(true);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mVideoSeekDragging = false;
                mMainHandler.removeCallbacks(mClearVideoSeekLock);
                mMainHandler.postDelayed(mClearVideoSeekLock, 150L);
                scheduleVideoBarHide();
            }
        });
        bar.addView(mVideoSeek);

        mVideoFitButton = videoButton(VIDEO_FIT_LABELS[mVideoFitMode]);
        mVideoFitButton.setOnClickListener(v -> cycleVideoFit());
        bar.addView(mVideoFitButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        bar.addView(row2, rowLp);

        Button zoomOut = videoButton("缩放 −");
        zoomOut.setOnClickListener(v -> adjustVideoZoom(-0.5f));
        row2.addView(zoomOut);

        Button zoomIn = videoButton("缩放 +");
        zoomIn.setOnClickListener(v -> adjustVideoZoom(0.5f));
        row2.addView(zoomIn);

        mVideoZoomText = new TextView(getContext());
        mVideoZoomText.setTextColor(0xFFFFD080);
        mVideoZoomText.setTextSize(15f);
        mVideoZoomText.setPadding(dp(6), 0, dp(6), 0);
        mVideoZoomText.setGravity(Gravity.CENTER);
        row2.addView(mVideoZoomText, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // INVISIBLE still measures the bar, so revealing it cannot move the video.
        bar.setVisibility(INVISIBLE);
        mStage.addView(bar, new FrameLayout.LayoutParams(
                1, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));
        mVideoBar = bar;
        updateVideoControls();
    }

    /** Thin white track sits on the video's bottom edge, above the controls. */
    private void setupVideoProgressTrack() {
        FrameLayout track = new FrameLayout(getContext());
        track.setBackgroundColor(0x44FFFFFF);
        track.setClickable(false);
        track.setFocusable(false);

        View fill = new View(getContext());
        fill.setBackgroundColor(0xFFFFFFFF);
        fill.setLayoutParams(new FrameLayout.LayoutParams(
                0, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.CENTER_VERTICAL));
        track.addView(fill);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                1, dp(2), Gravity.TOP | Gravity.LEFT);
        track.setLayoutParams(lp);
        track.setVisibility(GONE);
        mStage.addView(track);
        mVideoProgressTrack = track;
        mVideoProgressFill = fill;
    }

    private void layoutVideoOverlays() {
        if (mVideoBar == null || mVideoProgressTrack == null || mVideoRect.isEmpty()) {
            return;
        }
        int left = Math.round(mVideoRect.left);
        int right = Math.round(mVideoRect.right);
        int bottom = Math.round(mVideoRect.bottom);
        int width = Math.max(1, right - left);
        int trackHeight = Math.max(1, dp(2));
        setVideoOverlayBounds(mVideoProgressTrack, left, bottom - trackHeight, width);
        setVideoOverlayBounds(mVideoBar, left,
                Math.max(Math.round(mVideoRect.top), bottom - mVideoBar.getMeasuredHeight()), width);
        mVideoProgressTrack.setVisibility(isVideoPage() ? VISIBLE : GONE);
        updateVideoProgressTrackFill();
    }

    private void setVideoOverlayBounds(View view, int left, int top, int width) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (lp.width != width || lp.leftMargin != left || lp.topMargin != top) {
            lp.width = width;
            lp.leftMargin = left;
            lp.topMargin = top;
            view.setLayoutParams(lp);
        }
    }

    private void updateVideoProgressTrackFill() {
        if (mVideoProgressTrack == null || mVideoProgressFill == null) {
            return;
        }
        FrameLayout.LayoutParams tlp =
                (FrameLayout.LayoutParams) mVideoProgressTrack.getLayoutParams();
        int total = Math.max(1, tlp.width);
        float fraction = 0f;
        MediaPlayer player = mMediaPlayer;
        if (mVideoPrepared && player != null) {
            try {
                int duration = player.getDuration();
                if (duration > 0) {
                    int position = mVideoSeeking ? mVideoSeekTargetMs : player.getCurrentPosition();
                    fraction = Math.max(0f, Math.min(1f,
                            (float) position / (float) duration));
                }
            } catch (Exception ignored) {
            }
        }
        int fillW = Math.round(total * fraction);
        FrameLayout.LayoutParams flp =
                (FrameLayout.LayoutParams) mVideoProgressFill.getLayoutParams();
        if (flp.width != fillW) {
            flp.width = fillW;
            mVideoProgressFill.setLayoutParams(flp);
        }
    }

    private Button videoButton(String text) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(0xFF33333F);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setPadding(dp(2), 0, dp(2), 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void showVideoBar(boolean visible) {
        if (mVideoBar == null) {
            return;
        }
        int visibility = visible ? VISIBLE : INVISIBLE;
        if (mVideoBar.getVisibility() == visibility) {
            return;
        }
        mVideoBar.setVisibility(visibility);
        // The thin track and its tick are independent of control visibility.
        if (visible) {
            updateVideoControls();
            updateVideoProgressTrackFill();
        }
    }

    private void updateVideoControls() {
        if (mVideoBar == null) {
            return;
        }
        if (mVideoPlayButton != null) {
            mVideoPlayButton.setEnabled(mMediaPlayer == null || mVideoPrepared);
            mVideoPlayButton.setText(isVideoPlaying() ? "⏸ 暂停" : "▶ 播放");
        }
        if (mVideoSeek != null) {
            mVideoSeek.setEnabled(mVideoPrepared && mMediaPlayer != null);
        }
        if (mVideoFitButton != null) {
            mVideoFitButton.setText(VIDEO_FIT_LABELS[mVideoFitMode]);
        }
        if (mVideoZoomText != null) {
            mVideoZoomText.setText(mVideoZoom + "×");
        }
    }

    /** Runs for the whole lifetime of a video page, regardless of whether the
     *  hover control strip is showing. */
    private void updateVideoProgress() {
        updateVideoProgressTrackFill();
        MediaPlayer player = mMediaPlayer;
        boolean barVisible = mVideoBar != null && mVideoBar.getVisibility() == VISIBLE;
        if (barVisible && mVideoPrepared && player != null && !mVideoSeekDragging) {
            try {
                int duration = player.getDuration();
                int position = displayVideoPosition(player);
                if (duration > 0 && mVideoSeek != null) {
                    mVideoSeek.setProgress((int) (position * 1000L / duration));
                }
                if (mVideoTime != null) {
                    mVideoTime.setText(formatTime(position) + " / " + formatTime(duration));
                }
            } catch (Exception ignored) {
            }
        }
        if (barVisible) {
            updateVideoControls();
        }
        // Keep a single chained tick; startVideoTick() removes any pending one.
        mMainHandler.removeCallbacks(mVideoTick);
        if (isVideoPage() && mVideoPrepared && player != null && !mDestroyed) {
            mMainHandler.postDelayed(mVideoTick, 400L);
        }
    }

    private void startVideoTick() {
        mMainHandler.removeCallbacks(mVideoTick);
        mMainHandler.post(mVideoTick);
    }

    private void stopVideoTick() {
        mMainHandler.removeCallbacks(mVideoTick);
    }

    private static String formatTime(int millis) {
        int total = Math.max(0, millis) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60);
    }

    private void toggleVideoPlay() {
        MediaPlayer player = mMediaPlayer;
        if (player == null) {
            if (isVideoPage()) {
                playVideo(mPages.get(mIndex));
            }
            return;
        }
        if (!mVideoPrepared) {
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
            } else {
                startPreparedVideo(player);
            }
        } catch (Exception ignored) {
        }
        updateVideoControls();
    }

    private void startPreparedVideo(MediaPlayer player) {
        if (!mVideoPrepared || player == null || player != mMediaPlayer
                || mDestroyed || mVideoPaused || !isVisible()) {
            return;
        }
        try {
            player.start();
            mVideoCompleted = false;
            mMainHandler.removeCallbacks(mHideHint);
            mHideHint.run();
        } catch (Exception e) {
            Log.w(TAG, "video start failed", e);
        }
    }

    private void seekVideo(int deltaMs) {
        MediaPlayer player = mMediaPlayer;
        if (!mVideoPrepared || player == null) {
            return;
        }
        try {
            int duration = player.getDuration();
            // Accumulate from the pending TARGET, not the lagging playback
            // position, so repeated stick nudges (350ms cooldown) add up.
            int base = mVideoSeeking ? mVideoSeekTargetMs : player.getCurrentPosition();
            int position = Math.max(0, Math.min(duration, base + deltaMs));
            mVideoSeekGeneration++;
            mVideoSeekTargetMs = position;
            mVideoSeeking = true;
            mVideoSeekStartedAt = android.os.SystemClock.uptimeMillis();
            mMainHandler.removeCallbacks(mClearVideoSeekLock);
            player.seekTo(position);
            mVideoCompleted = false;
            // Paint the target immediately; the tick honours the lock.
            paintVideoPosition(position, duration);
        } catch (Exception ignored) {
        }
        updateVideoControls();
    }

    /** Paints the seek bar, time label and thin fill from an explicit position
     *  instead of player.getCurrentPosition() (which lags during a seek). */
    /** Position the UI should show: while a seek is in flight, the decoder's
     *  getCurrentPosition() still lags behind, so use the locked target. */
    private int displayVideoPosition(MediaPlayer p) {
        if (mVideoSeeking) {
            return mVideoSeekTargetMs;
        }
        return p != null ? p.getCurrentPosition() : 0;
    }

    private void paintVideoPosition(int position, int duration) {
        if (mVideoSeek != null && duration > 0) {
            mVideoSeek.setProgress((int) (position * 1000L / duration));
        }
        if (mVideoTime != null) {
            mVideoTime.setText(formatTime(position) + " / " + formatTime(duration));
        }
        updateVideoProgressTrackFill();
    }

    private void cycleVideoFit() {
        mVideoFitMode = (mVideoFitMode + 1) % VIDEO_FIT_LABELS.length;
        mSettings.put(MangaSettings.READER_VIDEO_FIT, mVideoFitMode);
        applyVideoTransform();
        updateVideoControls();
        showZoomHint("视频缩放：" + VIDEO_FIT_LABELS[mVideoFitMode]);
    }

    private void adjustVideoZoom(float delta) {
        mVideoZoom = clamp(mVideoZoom + delta, 0.5f, 4.0f);
        mSettings.put(MangaSettings.READER_VIDEO_ZOOM, mVideoZoom);
        applyVideoTransform();
        updateVideoControls();
        showZoomHint("视频倍率：" + mVideoZoom + "×");
    }

    private void applyVideoTransform() {
        if (mVideoWidth <= 0 || mVideoHeight <= 0
                || mVideoTexture.getWidth() <= 0 || mVideoTexture.getHeight() <= 0) {
            return;
        }
        final float vw = mVideoTexture.getWidth();
        final float vh = mVideoTexture.getHeight();
        // Fit against the WHOLE canvas, never against space left by controls.
        final float bw = mVideoWidth;
        final float bh = mVideoHeight;
        float scale;
        switch (mVideoFitMode) {
            case 1:
                scale = vh / bh;
                break;
            case 2:
                scale = vw / bw;
                break;
            case 3:
                scale = Math.min(1.0f, Math.min(vw / bw, vh / bh));
                break;
            default:
                scale = Math.min(vw / bw, vh / bh);
                break;
        }
        scale *= mVideoZoom;
        final float drawWidth = bw * scale;
        final float drawHeight = bh * scale;
        final float left = (vw - drawWidth) / 2f;
        final float top = (vh - drawHeight) / 2f;
        Matrix matrix = new Matrix();
        // TextureView already stretches the buffer to its bounds; undo that stretch per axis.
        matrix.setScale(drawWidth / vw, drawHeight / vh);
        matrix.postTranslate(left, top);
        mVideoTexture.setTransform(matrix);
        // Explicit zoom may crop the picture; overlays must stay inside the visible viewport.
        float textureX = mVideoTexture.getX();
        float textureY = mVideoTexture.getY();
        mVideoRect.set(textureX + Math.max(0f, left), textureY + Math.max(0f, top),
                textureX + Math.min(vw, left + drawWidth),
                textureY + Math.min(vh, top + drawHeight));
        if (!mVideoRect.intersect(0f, 0f, mStage.getWidth(), mStage.getHeight())) {
            mVideoRect.setEmpty();
        }
        layoutVideoOverlays();
        updateVideoBarVisibility();
        String transformLog = "video transform view=" + vw + "x" + vh + " buffer=" + bw + "x" + bh
                + " draw=" + drawWidth + "x" + drawHeight + " offset=" + left + "," + top
                + " visible=" + mVideoRect + " fit=" + mVideoFitMode + " zoom=" + mVideoZoom;
        if (!transformLog.equals(mLastVideoTransformLog)) {
            mLastVideoTransformLog = transformLog;
            Log.i(TAG, transformLog);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void playVideo(File page) {
        if (mDestroyed || !isVideoPage() || !page.equals(mPages.get(mIndex))) {
            return;
        }
        // Page opening and surface availability can request the same player concurrently.
        if (mMediaPlayer != null && page.equals(mVideoPage)) {
            return;
        }
        if (mVideoSurface == null || !mVideoSurface.isValid()) {
            mPendingVideoPage = page;
            return; // Normal asynchronous surface creation, not a playback failure.
        }
        Log.i(TAG, "playVideo file=" + page.getName());
        stopVideo(false);
        final int generation = mLoadGeneration;
        final MediaPlayer player = new MediaPlayer();
        mVideoPrepared = false;
        mMediaPlayer = player;
        mVideoPage = page;
        mVideoSeeking = false;
        mMainHandler.removeCallbacks(mClearVideoSeekLock);
        showLoading("正在准备视频…", -1, -1, -1, -1);
        showHint("正在准备视频…");
        updateVideoControls();
        // Seek completion is the authoritative moment to release the UI lock.
        // Without this the 400ms tick would keep showing the pre-seek position
        // until the decoder caught up, which reads as "fast-forward failed".
        player.setOnSeekCompleteListener(mp -> {
            mMainHandler.post(() -> {
                if (player != mMediaPlayer || mDestroyed) return;
                final int gen = mVideoSeekGeneration;
                // Paint our target, NOT getCurrentPosition(): at this instant
                // the decoder may still report the pre-seek position.
                paintVideoPosition(mVideoSeekTargetMs, mp.getDuration());
                // Drop stale completions from an earlier seek so they cannot
                // release a newer seek's lock prematurely.
                if (gen != mVideoSeekGeneration) return;
                mMainHandler.removeCallbacks(mClearVideoSeekLock);
                mClearVideoSeekLock.run();
            });
        });
        player.setOnPreparedListener(mp -> {
            if (player != mMediaPlayer || generation != mLoadGeneration || mDestroyed) {
                return;
            }
            // Must precede transforms: size/layout can update progress immediately.
            mVideoPrepared = true;
            hideLoading();
            mVideoWidth = mp.getVideoWidth();
            mVideoHeight = mp.getVideoHeight();
            Log.i(TAG, "video prepared " + mVideoWidth + "x" + mVideoHeight
                    + " duration=" + mp.getDuration());
            applyVideoTransform();
            updateVideoControls();
            startVideoTick();
            if (mSettings.bool(MangaSettings.PROGRESS_ENABLED)
                    && mSettings.bool(MangaSettings.PROGRESS_VIDEO)
                    && mSettings.bool(MangaSettings.READER_VIDEO_RESUME)) {
                int saved = progressPrefs().getInt(videoProgressKey(page), 0);
                if (saved > 0 && saved < mp.getDuration()) {
                    mVideoSeekGeneration++;
                    mVideoSeekTargetMs = saved;
                    mVideoSeeking = true;
                    mVideoSeekStartedAt = android.os.SystemClock.uptimeMillis();
                    mMainHandler.removeCallbacks(mClearVideoSeekLock);
                    mMainHandler.postDelayed(mClearVideoSeekLock, 150L);
                    mp.seekTo(saved);
                }
            }
            startPreparedVideo(mp);
            updateVideoControls();
        });
        player.setOnVideoSizeChangedListener((mp, width, height) -> {
            if (player == mMediaPlayer && generation == mLoadGeneration
                    && !mDestroyed && width > 0 && height > 0) {
                mVideoWidth = width;
                mVideoHeight = height;
                applyVideoTransform();
            }
        });
        player.setOnCompletionListener(mp -> {
            if (player != mMediaPlayer || generation != mLoadGeneration
                    || !mVideoPrepared || mDestroyed) {
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
            if (player == mMediaPlayer && generation == mLoadGeneration && !mDestroyed) {
                mVideoPrepared = false;
                // Do NOT auto-advance on error. The next page is often a huge
                // image, so an unsupported codec turned into a "video failed"
                // hint followed immediately by a fatal Canvas crash.
                Log.e(TAG, "video error what=" + what + " extra=" + extra
                        + " file=" + page.getName());
                stopVideo(false);
                showHint("视频无法播放(what=" + what + "/extra=" + extra
                        + ")：" + page.getName());
            }
            return true;
        });
        try {
            // Original, proven path-based setup: this is what worked with
            // picture + sound in every earlier build.
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
        stopVideoTick();
        MediaPlayer player = mMediaPlayer;
        // Save BEFORE clearing mMediaPlayer, otherwise persistState() has no
        // player to read the current playback position from.
        if (savePosition) {
            persistState();
        }
        mVideoPrepared = false;
        mMediaPlayer = null;
        mVideoPage = null;
        mPendingVideoPage = null;
        mVideoSeekDragging = false;
        mVideoSeeking = false;
        mMainHandler.removeCallbacks(mClearVideoSeekLock);
        showVideoBar(false);
        updateVideoControls();
        if (player == null) {
            return;
        }
        try {
            player.setOnPreparedListener(null);
            player.setOnSeekCompleteListener(null);
            player.setOnCompletionListener(null);
            player.setOnVideoSizeChangedListener(null);
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
        mVideoPaused = true;
        persistState();
        MediaPlayer player = mMediaPlayer;
        if (mVideoPrepared && player != null && isVideoPlaying()) {
            try {
                player.pause();
            } catch (Exception ignored) {
            }
        }
        cancelAuto();
    }

    @Override
    public void onResume() {
        mVideoPaused = false;
        updateWidget();
        MediaPlayer player = mMediaPlayer;
        if (isVisible() && mVideoPrepared && player != null && !mVideoCompleted) {
            startPreparedVideo(player);
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
            if (mVideoPrepared && player != null && isVideoPlaying()) {
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
            mPreloadExecutor.shutdownNow();
            mScaleUpdatePending = false;
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
        if (mDestroyed || surface != mVideoTexture.getSurfaceTexture()) {
            return;
        }
        if (surface != mVideoSurfaceTexture || mVideoSurface == null) {
            if (mVideoSurface != null) {
                stopVideo(true);
                mVideoSurface.release();
            }
            mVideoSurfaceTexture = surface;
            mVideoSurface = new Surface(surface);
        }
        Log.i(TAG, "surface available " + width + "x" + height + " video=" + isVideoPage());
        if (isVideoPage() && (mPages.get(mIndex).equals(mPendingVideoPage)
                || mSettings.bool(MangaSettings.READER_VIDEO_AUTOPLAY))) {
            playVideo(mPages.get(mIndex));
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        if (!mDestroyed && surface == mVideoSurfaceTexture) {
            applyVideoTransform();
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (surface != mVideoSurfaceTexture) {
            return true;
        }
        File pending = mVideoPage != null ? mVideoPage : mPendingVideoPage;
        stopVideo(true);
        mPendingVideoPage = mDestroyed ? null : pending;
        if (mVideoSurface != null) {
            mVideoSurface.release();
            mVideoSurface = null;
        }
        mVideoSurfaceTexture = null;
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
