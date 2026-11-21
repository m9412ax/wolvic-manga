/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Downloads ONE online chapter into a local cache folder, page by page in the
 * EXACT order of the file browser (SMB/115 links are constant-speed, so
 * parallel downloads only punch holes and stall at the slowest page).
 *
 * Videos are NOT downloaded: a tiny marker file is written and the reader
 * opens a MediaDataSource that streams the bytes on demand, so playback starts
 * instantly (crucial for LAN SMB). The whole remote cache is capped by the
 * REMOTE_CACHE_MB setting with oldest-chapter-first eviction.
 */
public final class RemoteChapterLoader {

    private static final String TAG = "RemoteLoader";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Progress { void onProgress(int done, int total); }
    public interface Callback { void onResult(File dir, String error); }

    private RemoteChapterLoader() {}

    /** Root of all remote chapter caches: <cache>/remote-manga/<hash>/<chapter>. */
    public static File remoteCacheRoot(Context context) {
        File base = context.getExternalCacheDir();
        if (base == null) base = context.getCacheDir();
        return new File(base, "remote-manga");
    }

    public static void download(final Context context,
                                final RemoteManga manga,
                                final RemoteManga.ChapterInfo chapter,
                                final File dir,
                                final int startPage,
                                final Progress progress,
                                final Callback callback) {
        new Thread(() -> {
            try {
                final List<RemoteManga.PageInfo> pages = manga.listPages(chapter);
                if (pages == null || pages.isEmpty()) {
                    throw new IllegalStateException("章节没有可读取的图片或视频");
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("无法创建缓存目录: " + dir.getAbsolutePath());
                }
                final int total = pages.size();
                final int[] done = {0};
                Log.i(TAG, "download " + manga.key() + "/" + chapter.name
                        + " pages=" + total + " (sequential)");

                boolean opened = false;
                for (int i = 0; i < total; i++) {
                    RemoteManga.PageInfo p = pages.get(i);
                    File out = cachedFile(dir, i, p.nameHint);
                    try {
                        if (!isValidCached(out)) {
                            if (isVideoName(p.nameHint)) {
                                // Marker instead of the full video: reader will
                                // stream it lazily through MediaDataSource.
                                RemoteManga.writeVideoMarker(out, p);
                                Log.i(TAG, "video marker " + out.getName()
                                        + " for " + p.nameHint);
                            } else {
                                File tmp = new File(out.getAbsolutePath() + ".tmp");
                                manga.fetchPage(p, tmp);
                                if (!tmp.exists() || tmp.length() == 0L) {
                                    throw new IllegalStateException("第 " + (i + 1) + " 页为空");
                                }
                                if (out.exists() && !out.delete()) Log.w(TAG, "delete old " + out);
                                if (!tmp.renameTo(out)) {
                                    throw new IllegalStateException("重命名失败: " + out);
                                }
                            }
                        }
                    } catch (Throwable e) {
                        Log.w(TAG, "page " + i + " failed: " + e.getMessage());
                    }
                    final int d = ++done[0];
                    MAIN.post(() -> {
                        if (progress != null) progress.onProgress(d, total);
                    });
                    // Hand the folder back as soon as the first page lands.
                    if (!opened) {
                        opened = true;
                        MAIN.post(() -> callback.onResult(dir, null));
                    }
                    if (d % 8 == 0) enforceCacheCap(context, dir);
                }
                enforceCacheCap(context, dir);
                Log.i(TAG, "download done " + chapter.name + " pages=" + done[0]);
            } catch (Throwable e) {
                Log.e(TAG, "download failed " + e.getMessage(), e);
                final String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                MAIN.post(() -> callback.onResult(null, msg));
            }
        }, "remote-chapter-download").start();
    }

    /** Warms up only the first [maxPages] pages (sequential, silent). */
    public static void prefetch(final RemoteManga manga,
                                final RemoteManga.ChapterInfo chapter,
                                final File dir, final int maxPages) {
        new Thread(() -> {
            try {
                List<RemoteManga.PageInfo> pages = manga.listPages(chapter);
                if (pages == null || pages.isEmpty()) return;
                if (!dir.exists() && !dir.mkdirs()) return;
                int n = Math.min(maxPages, pages.size());
                for (int i = 0; i < n; i++) {
                    RemoteManga.PageInfo p = pages.get(i);
                    File out = cachedFile(dir, i, p.nameHint);
                    if (isValidCached(out)) continue;
                    if (isVideoName(p.nameHint)) {
                        RemoteManga.writeVideoMarker(out, p);
                        continue;
                    }
                    File tmp = new File(out.getAbsolutePath() + ".tmp");
                    manga.fetchPage(p, tmp);
                    if (!tmp.exists() || tmp.length() == 0L) continue;
                    if (out.exists()) out.delete();
                    tmp.renameTo(out);
                }
                Log.i(TAG, "prefetch " + chapter.name + " " + n + " pages");
            } catch (Throwable e) {
                Log.w(TAG, "prefetch failed: " + e.getMessage());
            }
        }, "remote-prefetch").start();
    }

    /** Evicts oldest chapter folders until the remote cache is under the cap.
     *  Never deletes [protectDir] (the chapter currently being read). */
    public static void enforceCacheCap(Context context, File protectDir) {
        try {
            File root = remoteCacheRoot(context);
            if (!root.isDirectory()) return;
            long cap = MangaSettings.get(context).integer(MangaSettings.REMOTE_CACHE_MB)
                    * 1024L * 1024L;
            List<File> chapters = new ArrayList<>();
            File[] mangas = root.listFiles();
            if (mangas != null) {
                for (File m : mangas) {
                    if (!m.isDirectory()) continue;
                    File[] chs = m.listFiles();
                    if (chs == null) continue;
                    for (File ch : chs) if (ch.isDirectory()) chapters.add(ch);
                }
            }
            // Clean .tmp leftovers first (never readable, always count against cap).
            for (File ch : chapters) {
                File[] fs = ch.listFiles();
                if (fs == null) continue;
                for (File f : fs) if (f.isFile() && f.getName().endsWith(".tmp")) f.delete();
            }
            long total = 0L;
            for (File ch : chapters) total += dirSize(ch);
            if (total <= cap) return;
            chapters.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            String protect = protectDir == null ? "" : protectDir.getAbsolutePath();
            for (File ch : chapters) {
                if (total <= cap) break;
                if (ch.getAbsolutePath().equals(protect)) continue;
                long s = dirSize(ch);
                if (ch.delete()) {
                    total -= s;
                    Log.i(TAG, "evicted " + ch.getAbsolutePath() + " (" + s + " bytes)");
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "enforceCacheCap failed: " + e.getMessage());
        }
    }

    private static long dirSize(File dir) {
        long n = 0L;
        File[] fs = dir.listFiles();
        if (fs == null) return 0L;
        for (File f : fs) if (f != null && f.isFile() && !f.getName().endsWith(".tmp")) n += f.length();
        return n;
    }

    private static boolean isVideoName(String name) {
        if (name == null) return false;
        String[] V = {"mp4", "mkv", "webm", "avi", "mov", "3gp", "ts", "m4v", "hevc", "h265", "av1"};
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : V) if (e.equals(ext)) return true;
        return false;
    }

    private static File cachedFile(File dir, int index, String nameHint) {
        String ext = "jpg";
        if (nameHint != null) {
            int dot = nameHint.lastIndexOf('.');
            if (dot >= 0 && dot < nameHint.length() - 1) {
                String e = nameHint.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (e.length() <= 5) ext = e;
            }
        }
        return new File(dir, String.format(Locale.ROOT, "%04d.%s", index, ext));
    }

    private static boolean isValidCached(File f) {
        return f.exists() && !f.getName().endsWith(".tmp") && f.length() > 0;
    }
}
