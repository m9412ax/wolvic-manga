/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downloads ONE online chapter page-by-page into a local cache folder, with
 * the same layered priority as extension sources: page 0 first (folder handed
 * back immediately -> "open now"), leading 4 pages, 4 pages after the reader's
 * entry page, then the rest concurrently. Already-cached pages are skipped.
 */
public final class RemoteChapterLoader {

    private static final String TAG = "RemoteLoader";
    private static final int CONCURRENCY = 6;
    private static final int HEAD = 4;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Progress { void onProgress(int done, int total); }
    public interface Callback { void onResult(File dir, String error); }

    private RemoteChapterLoader() {}

    public static void download(final RemoteManga manga,
                                final RemoteManga.ChapterInfo chapter,
                                final File dir,
                                final int startPage,
                                final Progress progress,
                                final Callback callback) {
        new Thread(() -> {
            final boolean[] opened = {false};
            try {
                final List<RemoteManga.PageInfo> pages = manga.listPages(chapter);
                if (pages == null || pages.isEmpty()) {
                    throw new IllegalStateException("章节页面列表为空");
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("无法创建缓存目录: " + dir.getAbsolutePath());
                }
                final int total = pages.size();
                final int start = Math.max(0, Math.min(startPage, total - 1));
                Log.i(TAG, "download " + manga.key() + "/" + chapter.name
                        + " pages=" + total + " start=" + start);

                final Set<Integer> reported = java.util.Collections.synchronizedSet(new HashSet<>());
                final AtomicInteger done = new AtomicInteger(0);
                final Semaphore sem = new Semaphore(CONCURRENCY);
                final ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);

                // Helper that downloads a single page (idempotent, thread-safe).
                final OnePage onePage = i -> {
                    if (i < 0 || i >= total || !reported.add(i)) return;
                    RemoteManga.PageInfo p = pages.get(i);
                    File out = cachedFile(dir, i, p.nameHint);
                    try {
                        if (!isValidCached(out)) {
                            File tmp = new File(out.getAbsolutePath() + ".tmp");
                            manga.fetchPage(p, tmp);
                            if (!tmp.exists() || tmp.length() == 0L) {
                                throw new IllegalStateException("第 " + (i + 1) + " 页下载为空");
                            }
                            if (out.exists() && !out.delete()) Log.w(TAG, "delete old " + out);
                            if (!tmp.renameTo(out)) throw new IllegalStateException("重命名失败: " + out);
                        }
                    } catch (Throwable e) {
                        Log.w(TAG, "page " + i + " failed: " + e.getMessage());
                    } finally {
                        int d = done.incrementAndGet();
                        final int dd = d;
                        MAIN.post(() -> {
                            if (progress != null) progress.onProgress(dd, total);
                        });
                    }
                };

                try {
                    // Stage 1: first page alone, hand the folder straight back.
                    onePage.run(0);
                    if (!opened[0]) {
                        opened[0] = true;
                        MAIN.post(() -> callback.onResult(dir, null));
                    }
                    // Stage 2: leading window.
                    awaitAll(pool, range(1, Math.min(HEAD, total)), onePage, sem);
                    // Stage 3: pages just after the reader's entry page.
                    awaitAll(pool, range(start + 1, Math.min(start + 5, total)), onePage, sem);
                    // Stage 4: everything else, capped concurrency.
                    awaitAll(pool, range(0, total), onePage, sem);
                } finally {
                    pool.shutdown();
                }
                Log.i(TAG, "download done " + chapter.name + " pages=" + done.get());
            } catch (Throwable e) {
                Log.e(TAG, "download failed " + e.getMessage(), e);
                final String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (!opened[0]) MAIN.post(() -> callback.onResult(null, msg));
            }
        }, "remote-chapter-download").start();
    }

    /** Warms up only the first [maxPages] pages of a chapter (cheap pre-cache
     *  for instant chapter switching). Skips already-cached pages. */
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

    private interface OnePage { void run(int i) throws Exception; }

    private static List<Integer> range(int from, int to) {
        List<Integer> out = new ArrayList<>();
        for (int i = from; i < to; i++) out.add(i);
        return out;
    }

    private static void awaitAll(ExecutorService pool, List<Integer> indices,
                                 OnePage onePage, Semaphore sem) throws Exception {
        List<Future<?>> futures = new ArrayList<>();
        for (int i : indices) {
            futures.add(pool.submit(() -> {
                try {
                    sem.acquire();
                    try { onePage.run(i); } finally { sem.release(); }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable ignored) {
                    Log.w(TAG, "page task failed: " + ignored.getMessage());
                }
                return null;
            }));
        }
        for (Future<?> f : futures) f.get();
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
