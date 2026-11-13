/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Real chapter downloader: WebDAV GET -> local folder, with the settings applied. */
public final class MangaDownloader {

    private static final String TAG = "MangaDownloader";

    public interface Callback {
        void onUpdate(String status);
    }

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private ExecutorService mPool;
    private volatile boolean mBusy;

    public MangaDownloader(Context context) {
        mContext = context.getApplicationContext();
    }

    public boolean isBusy() {
        return mBusy;
    }

    public boolean canUseNetwork() {
        MangaSettings settings = MangaSettings.get(mContext);
        if (!settings.bool(MangaSettings.DOWNLOAD_ONLY_WIFI)) {
            return true;
        }
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = manager == null ? null : manager.getActiveNetworkInfo();
            return info != null && info.isConnected()
                    && info.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Throwable t) {
            return false;
        }
    }

    public void download(List<MangaWebDav.Entry> files, String baseUrl, String user,
                         String pass, File destination, Callback callback) {
        if (mBusy) {
            notify(callback, "已有下载任务在运行");
            return;
        }
        if (files == null || files.isEmpty()) {
            notify(callback, "没有可下载的文件");
            return;
        }
        if (!canUseNetwork()) {
            notify(callback, "已开启「仅 WiFi 下载」，当前不是 WiFi 网络");
            return;
        }

        MangaSettings settings = MangaSettings.get(mContext);
        int parallel = Math.max(1, Math.min(4, settings.integer(MangaSettings.DOWNLOAD_PARALLEL)));
        final int retries = Math.max(0, Math.min(5, settings.integer(MangaSettings.DOWNLOAD_RETRY)));
        mBusy = true;
        mPool = Executors.newFixedThreadPool(parallel);
        final AtomicInteger done = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);
        final int total = files.size();
        notify(callback, "开始下载 0/" + total);

        for (MangaWebDav.Entry entry : files) {
            mPool.execute(() -> {
                boolean ok = false;
                for (int attempt = 0; attempt <= retries && !ok; attempt++) {
                    try {
                        String url = MangaWebDav.resolve(baseUrl, entry.href);
                        MangaWebDav.download(url, user, pass,
                                new File(destination, entry.name), null);
                        ok = true;
                    } catch (Exception e) {
                        Log.w(TAG, "download failed " + entry.name + " attempt " + attempt
                                + ": " + e.getMessage());
                    }
                }
                if (!ok) {
                    failed.incrementAndGet();
                }
                int current = done.incrementAndGet();
                final String status = "下载 " + current + "/" + total
                        + (failed.get() > 0 ? " · 失败 " + failed.get() : "");
                notify(callback, status);
            });
        }
        mPool.shutdown();
        mMain.post(new Runnable() {
            @Override
            public void run() {
                if (done.get() < total) {
                    mMain.postDelayed(this, 300);
                    return;
                }
                mBusy = false;
                notify(callback, "下载完成 " + (total - failed.get()) + "/" + total
                        + (failed.get() > 0 ? "，失败 " + failed.get() : ""));
            }
        });
    }

    /** Removes cached chapters whose pages were all read. Consumes DOWNLOAD_DELETE_READ. */
    public int purgeReadChapters() {
        MangaSettings settings = MangaSettings.get(mContext);
        if (!settings.bool(MangaSettings.DOWNLOAD_DELETE_READ)) {
            return 0;
        }
        File root = new File(settings.text(MangaSettings.DOWNLOAD_DIR));
        File[] chapters = root.listFiles(File::isDirectory);
        if (chapters == null) {
            return 0;
        }
        SharedPreferencesCompat prefs = new SharedPreferencesCompat(settings);
        int removed = 0;
        for (File chapter : chapters) {
            File[] pages = chapter.listFiles();
            if (pages == null || pages.length == 0) {
                continue;
            }
            int saved = prefs.page(chapter);
            if (saved >= pages.length - 1) {
                for (File page : pages) {
                    page.delete();
                }
                if (chapter.delete()) {
                    removed++;
                }
            }
        }
        return removed;
    }

    private static final class SharedPreferencesCompat {
        private final MangaSettings mSettings;

        SharedPreferencesCompat(MangaSettings settings) {
            mSettings = settings;
        }

        int page(File folder) {
            return mSettings.progressPrefs().getInt("page:" + folder.getAbsolutePath(), -1);
        }
    }

    private void notify(Callback callback, String status) {
        Log.i(TAG, status);
        if (callback == null) {
            return;
        }
        mMain.post(() -> callback.onUpdate(status));
    }
}
