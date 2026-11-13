/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.ui.widgets;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.igalia.wolvic.R;
import com.igalia.wolvic.ui.adapters.MangaCoverAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Folder reader with naturally sorted videos first and images after them. Video
 * completion automatically advances to the next entry. TextureView is required
 * here: a VideoView/SurfaceView has a separate surface and is not drawn into a
 * Wolvic UIWidget's offscreen surface.
 */
public class MangaReaderWidget extends UIWidget implements TextureView.SurfaceTextureListener {

    private static final String[] IMAGE_EXTENSIONS = {"jpg", "jpeg", "png", "webp", "gif"};
    private static final String[] VIDEO_EXTENSIONS = {"mp4", "mkv", "webm", "avi"};
    private static final int FALLBACK_TARGET_PX = 1200;
    private static final String PROGRESS_PREFS = "manga_reader_progress";

    private ImageView mPageImage;
    private TextureView mVideoTexture;
    private TextView mPageCounter;

    private final List<File> mPages = new ArrayList<>();
    private File mFolder;
    private int mIndex;
    private int mLoadGeneration;
    private Bitmap mCurrent;
    private Runnable mOnBack;
    private boolean mDestroyed;
    private boolean mVideoCompleted;

    private MediaPlayer mMediaPlayer;
    private Surface mVideoSurface;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public MangaReaderWidget(Context aContext) {
        super(aContext);
        initialize();
    }

    private void initialize() {
        // TextureView must be composited with a hardware-accelerated canvas.
        setIsHardwareAccelerationEnabled(true);
        LayoutInflater.from(getContext()).inflate(R.layout.manga_reader, this, true);

        mPageImage = findViewById(R.id.pageImage);
        mVideoTexture = findViewById(R.id.videoTexture);
        mPageCounter = findViewById(R.id.pageCounter);
        Button prevButton = findViewById(R.id.prevButton);
        Button nextButton = findViewById(R.id.nextButton);
        Button backButton = findViewById(R.id.backButton);

        if (mVideoTexture != null) {
            mVideoTexture.setSurfaceTextureListener(this);
        }
        if (prevButton != null) {
            prevButton.setOnClickListener(v -> showPage(mIndex - 1));
        }
        if (nextButton != null) {
            nextButton.setOnClickListener(v -> showPage(mIndex + 1));
        }
        if (backButton != null) {
            backButton.setOnClickListener(v -> onDismiss());
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

    public void setOnBackListener(Runnable onBack) {
        mOnBack = onBack;
    }

    /** Loads direct child media; videos are naturally sorted before images. */
    public void setFolder(File folder) {
        stopVideo(true);
        mLoadGeneration++;
        mFolder = folder;
        mPages.clear();
        mIndex = 0;

        List<File> videos = new ArrayList<>();
        List<File> images = new ArrayList<>();
        if (folder != null && folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!file.isFile()) {
                        continue;
                    }
                    if (hasExtension(file.getName(), VIDEO_EXTENSIONS)) {
                        videos.add(file);
                    } else if (hasExtension(file.getName(), IMAGE_EXTENSIONS)) {
                        images.add(file);
                    }
                }
            }
        }
        videos.sort((a, b) -> MangaCoverAdapter.naturalCompare(a.getName(), b.getName()));
        images.sort((a, b) -> MangaCoverAdapter.naturalCompare(a.getName(), b.getName()));
        mPages.addAll(videos);
        mPages.addAll(images);

        int savedIndex = folder == null ? 0 : getPreferences().getInt(pageProgressKey(), 0);
        showPage(Math.max(0, Math.min(savedIndex, Math.max(0, mPages.size() - 1))));
    }

    private void showPage(int index) {
        if (mDestroyed || index < 0 || index >= mPages.size()) {
            if (mPages.isEmpty()) {
                showEmptyState();
            }
            return;
        }

        stopVideo(true);
        final int requestGeneration = ++mLoadGeneration;
        mIndex = index;
        mVideoCompleted = false;
        getPreferences().edit().putInt(pageProgressKey(), mIndex).apply();

        final File page = mPages.get(mIndex);
        mPageCounter.setText((mIndex + 1) + " / " + mPages.size());

        if (hasExtension(page.getName(), VIDEO_EXTENSIONS)) {
            clearImage();
            mPageImage.setVisibility(GONE);
            mVideoTexture.setVisibility(VISIBLE);
            playVideo(page);
            return;
        }

        mVideoTexture.setVisibility(GONE);
        mPageImage.setVisibility(VISIBLE);
        final int targetWidth = mPageImage.getWidth() > 0
                ? mPageImage.getWidth() : FALLBACK_TARGET_PX;
        final int targetHeight = mPageImage.getHeight() > 0
                ? mPageImage.getHeight() : FALLBACK_TARGET_PX;

        mExecutor.execute(() -> {
            final Bitmap bitmap = decodePage(page, targetWidth, targetHeight);
            mMainHandler.post(() -> {
                if (mDestroyed || requestGeneration != mLoadGeneration) {
                    if (bitmap != null) {
                        bitmap.recycle();
                    }
                    return;
                }
                Bitmap old = mCurrent;
                mCurrent = bitmap;
                mPageImage.setImageBitmap(bitmap);
                if (old != null && old != bitmap && !old.isRecycled()) {
                    old.recycle();
                }
            });
        });
    }

    private void showEmptyState() {
        mLoadGeneration++;
        stopVideo(true);
        mPageCounter.setText("0 / 0");
        mVideoTexture.setVisibility(GONE);
        mPageImage.setVisibility(VISIBLE);
        clearImage();
    }

    private void playVideo(File page) {
        if (mVideoSurface == null || !mVideoSurface.isValid() || mDestroyed) {
            return;
        }

        final MediaPlayer player = new MediaPlayer();
        mMediaPlayer = player;
        player.setOnPreparedListener(mp -> {
            if (player != mMediaPlayer || mDestroyed) {
                return;
            }
            int savedPosition = getPreferences().getInt(videoProgressKey(page), 0);
            if (savedPosition > 0 && savedPosition < mp.getDuration()) {
                mp.seekTo(savedPosition);
            }
            mp.start();
        });
        player.setOnCompletionListener(mp -> {
            if (player != mMediaPlayer || mDestroyed) {
                return;
            }
            mVideoCompleted = true;
            getPreferences().edit().putInt(videoProgressKey(page), 0).apply();
            if (mIndex + 1 < mPages.size()) {
                showPage(mIndex + 1);
            }
        });
        player.setOnErrorListener((mp, what, extra) -> {
            if (player == mMediaPlayer && !mDestroyed) {
                stopVideo(false);
                getPreferences().edit().putInt(videoProgressKey(page), 0).apply();
                if (mIndex + 1 < mPages.size()) {
                    showPage(mIndex + 1);
                } else {
                    mPageCounter.setText("视频无法播放 · " + page.getName());
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
                if (mIndex + 1 < mPages.size()) {
                    showPage(mIndex + 1);
                } else {
                    mPageCounter.setText("视频无法播放 · " + page.getName());
                }
            }
        }
    }

    private void stopVideo(boolean savePosition) {
        MediaPlayer player = mMediaPlayer;
        mMediaPlayer = null;
        if (player == null) {
            return;
        }
        if (savePosition && !mVideoCompleted && mFolder != null
                && mIndex >= 0 && mIndex < mPages.size()) {
            File page = mPages.get(mIndex);
            try {
                getPreferences().edit()
                        .putInt(videoProgressKey(page), player.getCurrentPosition())
                        .apply();
            } catch (Exception ignored) {
            }
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

    private void clearImage() {
        if (mPageImage != null) {
            mPageImage.setImageDrawable(null);
        }
        if (mCurrent != null && !mCurrent.isRecycled()) {
            mCurrent.recycle();
        }
        mCurrent = null;
    }

    @Override
    protected void onDismiss() {
        if (mDestroyed) {
            return;
        }
        hide(REMOVE_WIDGET);
        if (mOnBack != null) {
            mOnBack.run();
        }
    }

    @Override
    public void onPause() {
        MediaPlayer player = mMediaPlayer;
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.pause();
                }
                if (!mVideoCompleted && mIndex >= 0 && mIndex < mPages.size()) {
                    getPreferences().edit()
                            .putInt(videoProgressKey(mPages.get(mIndex)), player.getCurrentPosition())
                            .apply();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onResume() {
        MediaPlayer player = mMediaPlayer;
        if (isVisible() && player != null && !mVideoCompleted) {
            try {
                player.start();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void hide(@HideFlags int aHideFlags) {
        if (aHideFlags == REMOVE_WIDGET && !mDestroyed) {
            mDestroyed = true;
            mLoadGeneration++;
            stopVideo(true);
            mExecutor.shutdownNow();
            mMainHandler.removeCallbacksAndMessages(null);
            if (mVideoSurface != null) {
                mVideoSurface.release();
                mVideoSurface = null;
            }
            clearImage();
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
        if (mIndex >= 0 && mIndex < mPages.size()
                && hasExtension(mPages.get(mIndex).getName(), VIDEO_EXTENSIONS)) {
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

    private SharedPreferences getPreferences() {
        return getContext().getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE);
    }

    private String pageProgressKey() {
        return "page:" + (mFolder == null ? "" : mFolder.getAbsolutePath());
    }

    private static String videoProgressKey(File page) {
        return "video:" + page.getAbsolutePath();
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

    private static boolean hasExtension(String name, String[] extensions) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (lower.endsWith("." + ext)) {
                return true;
            }
        }
        return false;
    }
}
