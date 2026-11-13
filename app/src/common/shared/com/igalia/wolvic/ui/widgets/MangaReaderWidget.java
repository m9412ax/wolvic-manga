/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.widgets;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
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
import android.widget.TextView;
import android.widget.Toast;

import com.igalia.wolvic.R;
import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.browser.SettingsStore;
import com.igalia.wolvic.manga.MangaSettings;
import com.igalia.wolvic.ui.adapters.MangaCoverAdapter;

import java.io.File;
import java.util.ArrayList;
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

    private MangaSettings mSettings;
    private FrameLayout mRootView;
    private FrameLayout mStage;
    private FrameLayout mGotoDialog;
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

    public MangaReaderWidget(Context aContext) {
        super(aContext);
        initialize();
    }

    private void initialize() {
        mSettings = MangaSettings.get(getContext());
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

        mPageImage.setScaleType(ImageView.ScaleType.MATRIX);
        mPageImage.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or_, ob) -> applyImageTransform());
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
                if (mSettings.integer(MangaSettings.READER_DIRECTION) == 2 && mZoom <= 1.01f) {
                    mScrollY -= distanceY;
                    applyImageTransform();
                    return true;
                }
                return false;
            }
        });
        mStage.setOnTouchListener((view, event) -> mGestureDetector.onTouchEvent(event));

        setFocusableInTouchMode(true);
        setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN
                    || !mSettings.bool(MangaSettings.READER_VOLUME_KEYS)) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                pageDelta(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                pageDelta(1);
                return true;
            }
            return false;
        });

        Button confirm = findViewById(R.id.gotoConfirm);
        Button cancel = findViewById(R.id.gotoCancel);
        confirm.setOnClickListener(v -> confirmGoto());
        cancel.setOnClickListener(v -> mGotoDialog.setVisibility(GONE));

        applyDisplaySettings();
    }

    @Override
    protected void initializeWidgetPlacement(WidgetPlacement aPlacement) {
        Context context = getContext();
        int windowWidth = SettingsStore.getInstance(context).getWindowWidth();
        int windowHeight = SettingsStore.getInstance(context).getWindowHeight();
        aPlacement.width = (int) (windowWidth * 0.78f) + mBorderWidth * 2;
        aPlacement.height = (int) (windowHeight * 0.82f) + mBorderWidth * 2;
        aPlacement.worldWidth = WidgetPlacement.floatDimension(context, R.dimen.window_world_width)
                * (float) windowWidth / (float) SettingsStore.WINDOW_WIDTH_DEFAULT * 0.78f;
        aPlacement.translationY = WidgetPlacement.unitFromMeters(context, R.dimen.window_world_y) + 40.0f;
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
        mIndex = index;
        mVideoCompleted = false;
        mZoom = 1.0f;
        mScrollY = 0.0f;
        if (mSettings.bool(MangaSettings.PROGRESS_ENABLED)) {
            progressPrefs().edit().putInt(pageProgressKey(), mIndex).apply();
        }

        final File page = mPages.get(mIndex);
        applyDisplaySettings();
        mPageCounter.setText((mIndex + 1) + " / " + mPages.size());
        mGotoDialog.setVisibility(GONE);

        if (MangaSettings.hasExtension(page.getName(), mSettings.videoExtensions())) {
            mPageImage.setVisibility(GONE);
            mVideoTexture.setVisibility(VISIBLE);
            if (mSettings.bool(MangaSettings.READER_VIDEO_AUTOPLAY)) {
                playVideo(page);
            } else {
                showHint("点击画面播放视频");
            }
            return;
        }

        mVideoTexture.setVisibility(GONE);
        mPageImage.setVisibility(VISIBLE);
        Bitmap cached = mCache.get(page.getAbsolutePath());
        if (cached != null && !cached.isRecycled()) {
            mCurrent = cached;
            mPageImage.setImageBitmap(cached);
            mPageImage.post(this::applyImageTransform);
            finishPageLoad(generation);
            return;
        }
        mPageImage.setImageDrawable(null);
        mExecutor.execute(() -> {
            final Bitmap bitmap = decodePage(page);
            mMainHandler.post(() -> {
                if (mDestroyed || generation != mLoadGeneration) {
                    return;
                }
                mCurrent = bitmap;
                if (bitmap != null) {
                    mCache.put(page.getAbsolutePath(), bitmap);
                    mPageImage.setImageBitmap(bitmap);
                    mPageImage.post(this::applyImageTransform);
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
    }

    private Bitmap decodePage(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int max = Math.max(400, mSettings.integer(MangaSettings.ADV_MAX_DECODE_PX));
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = MangaCoverAdapter.computeInSampleSize(
                    bounds.outWidth, bounds.outHeight, max, max);
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
        int count = Math.max(0, Math.min(4, mSettings.integer(MangaSettings.READER_PRELOAD)));
        for (int i = 1; i <= count; i++) {
            int target = mIndex + i;
            if (target >= mPages.size()) {
                break;
            }
            final File file = mPages.get(target);
            if (mCache.get(file.getAbsolutePath()) != null
                    || MangaSettings.hasExtension(file.getName(), mSettings.videoExtensions())) {
                continue;
            }
            mExecutor.execute(() -> {
                Bitmap bitmap = decodePage(file);
                if (bitmap != null) {
                    mMainHandler.post(() -> {
                        if (!mDestroyed && mCache.get(file.getAbsolutePath()) == null) {
                            mCache.put(file.getAbsolutePath(), bitmap);
                        }
                    });
                }
            });
        }
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
        Bitmap bitmap = mCurrent;
        if (bitmap == null || mPageImage.getWidth() == 0 || mPageImage.getHeight() == 0) {
            return;
        }
        float vw = mPageImage.getWidth();
        float vh = mPageImage.getHeight();
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
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
        mPageImage.setImageMatrix(mMatrix);
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
