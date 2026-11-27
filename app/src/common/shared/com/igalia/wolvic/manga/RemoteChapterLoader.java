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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** One download per chapter folder. The reader's loadChapter and the
     *  library's prefetch can both request the same dir; without this guard
     *  several threads fetch the SAME pages into the SAME .tmp path, racing
     *  rename() and producing endless "SMB 下载为空" / black pages. */
    private static final Map<String, ActiveDownload> ACTIVE = new ConcurrentHashMap<>();

    private static final class ActiveDownload {
        final String key;
        final List<Progress> progresses = new ArrayList<>();
        final List<Callback> callbacks = new ArrayList<>();
        int lastDone, lastTotal;
        String lastName;
        long lastCurDone, lastCurTotal;
        /** Pages a late joiner needs first, in priority order. */
        final java.util.Set<Integer> urgent = new java.util.LinkedHashSet<>();
        ActiveDownload(String key) { this.key = key; }

        synchronized void reanchor(int page) {
            if (page > 0) {
                urgent.add(page);
            }
        }

        synchronized int takeUrgent() {
            if (urgent.isEmpty()) {
                return -1;
            }
            java.util.Iterator<Integer> it = urgent.iterator();
            int p = it.next();
            it.remove();
            return p;
        }

        synchronized void add(Progress p, Callback c) {
            if (p != null) {
                progresses.add(p);
                // Replay the latest snapshot so a late joiner sees real progress.
                if (lastTotal > 0) {
                    try { p.onProgress(lastDone, lastTotal, lastName, lastCurDone, lastCurTotal); }
                    catch (Throwable ignored) { }
                }
            }
            if (c != null) callbacks.add(c);
        }
        synchronized void progress(int done, int total, String name, long cd, long ct) {
            lastDone = done; lastTotal = total; lastName = name;
            lastCurDone = cd; lastCurTotal = ct;
            for (Progress p : progresses) {
                try { p.onProgress(done, total, name, cd, ct); } catch (Throwable ignored) { }
            }
        }
        /** Delivers the one-shot onDir result. Progress listeners stay attached
         *  so the reader keeps growing its page list as the rest lands. */
        synchronized void result(File dir, String error) {
            for (Callback c : callbacks) {
                try { c.onResult(dir, error); } catch (Throwable ignored) { }
            }
            callbacks.clear();
        }
        synchronized void finish() {
            progresses.clear();
            callbacks.clear();
        }
    }

    /** Progress of a sequential chapter download.
     *  done/total = pages finished / pages in chapter.
     *  curName/curDone/curTotal = the page currently being fetched, for the
     *  "downloading <file> (x/y)" progress line. curTotal<0 means unknown. */
    public interface Progress {
        void onProgress(int done, int total, String curName,
                        long curDone, long curTotal);
    }
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
        final String key = dir.getAbsolutePath();
        final ActiveDownload active = new ActiveDownload(key);
        ActiveDownload existing = ACTIVE.putIfAbsent(key, active);
        if (existing != null) {
            // Same chapter is already streaming. Piggyback on it, but if the
            // reader entered at a different page, pull that anchor forward:
            // otherwise tapping image #90 joins a download that started at #0
            // and the user stares at "从头下载" for minutes.
            existing.reanchor(startPage);
            existing.add(progress, callback);
            Log.i(TAG, "dedup join " + key + " anchor=" + startPage);
            return;
        }
        active.add(progress, callback);
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

                // Download order: the page the user actually tapped first, then
                // outward neighbours, then the rest. Previously it always began
                // at page 0, so tapping image #99 meant waiting for 98 pages
                // (~6 min of 4K downloads) before that image appeared.
                java.util.List<Integer> order = new java.util.ArrayList<>(total);
                int anchor = Math.max(0, Math.min(startPage, total - 1));
                order.add(anchor);
                for (int distance = 1; distance < total; distance++) {
                    int lo = anchor - distance;
                    int hi = anchor + distance;
                    if (lo >= 0) order.add(lo);
                    if (hi < total) order.add(hi);
                }

                boolean opened = false;
                // A late joiner (tap on a specific file) can push its page to
                // the front of an already-running download via reanchor().
                java.util.Set<Integer> urgentDone = new java.util.HashSet<>();
                for (int k = 0; k < order.size(); k++) {
                    int urgentIdx = active.takeUrgent();
                    int idx = (urgentIdx >= 0 && urgentIdx < total
                            && !urgentDone.contains(urgentIdx) && order.remove((Integer) urgentIdx))
                            ? urgentIdx : order.get(0);
                    if (urgentIdx >= 0) {
                        urgentDone.add(urgentIdx);
                    }
                    final int i = idx;
                    RemoteManga.PageInfo p = pages.get(i);
                    final File out = cachedFile(dir, i, p.nameHint);
                    final String curName = p.nameHint == null ? out.getName() : p.nameHint;
                    final long[] curBytes = {0L, p.size > 0 ? p.size : -1L};
                    final int pageIdx = i;
                    boolean landed = isValidCached(out);
                    if (!landed) {
                        try {
                            if (isVideoName(p.nameHint)) {
                                // Marker instead of the full video: reader will
                                // stream it lazily through MediaDataSource.
                                RemoteManga.writeVideoMarker(out, p);
                                landed = out.exists() && out.length() > 0;
                                Log.i(TAG, "video marker " + out.getName()
                                        + " for " + p.nameHint);
                            } else {
                                // Retry once: a transient SMB "下载为空" (server
                                // hiccup / connection rush) is common. Without a
                                // retry the page is a permanent black hole while
                                // the progress bar still counts it as done.
                                for (int attempt = 0; attempt < 2 && !landed; attempt++) {
                                    try {
                                        manga.fetchPage(p, out, (d0, t0) -> {
                                            curBytes[0] = d0;
                                            if (t0 > 0) curBytes[1] = t0;
                                            final long cd = curBytes[0], ct = curBytes[1];
                                            MAIN.post(() -> active.progress(done[0], total,
                                                    curName, cd, ct));
                                        });
                                        landed = out.exists() && out.length() > 0;
                                    } catch (Throwable e) {
                                        Log.w(TAG, "page " + pageIdx + " attempt " + attempt
                                                + " failed: " + e.getMessage());
                                        // A failed attempt may leave an empty .tmp/target;
                                        // remove it so the next attempt starts clean.
                                        File tmp = new File(out.getAbsolutePath() + ".tmp");
                                        if (tmp.exists()) tmp.delete();
                                        if (out.exists() && out.length() == 0) out.delete();
                                    }
                                }
                            }
                        } catch (Throwable e) {
                            Log.w(TAG, "page " + pageIdx + " failed: " + e.getMessage());
                        }
                    }
                    // Only pages that really landed count as done. Failed pages
                    // stay out of the counter, so the bar matches what is readable.
                    if (landed) done[0]++;
                    final int d = done[0];
                    final long cd = curBytes[0], ct = curBytes[1];
                    MAIN.post(() -> active.progress(d, total, curName, cd, ct));
                    // Hand the folder back as soon as the first page lands.
                    if (!opened) {
                        opened = true;
                        MAIN.post(() -> active.result(dir, null));
                    }
                    if (d % 8 == 0) enforceCacheCap(context, dir);
                }
                enforceCacheCap(context, dir);
                Log.i(TAG, "download done " + chapter.name + " pages=" + done[0]);
                MAIN.post(() -> {
                    active.progress(done[0], total, null, 0, 0);
                    active.result(dir, null);   // idempotent: callbacks already cleared
                    active.finish();
                    ACTIVE.remove(key, active);
                });
            } catch (Throwable e) {
                Log.e(TAG, "download failed " + e.getMessage(), e);
                final String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                MAIN.post(() -> {
                    active.result(null, msg);
                    active.finish();
                    ACTIVE.remove(key, active);
                });
            }
        }, "remote-chapter-download").start();
    }

    /**
     * Fetches ONE page on demand (the reader scrubbed to it and the bulk pass
     * has not reached it yet). Uses the same cachedFile() layout so a refresh
     * of the chapter folder sees the page at the correct index.
     */
    public static void fetchSingle(final Context context, final RemoteManga manga,
                                   final RemoteManga.ChapterInfo chapter,
                                   final File dir, final int pageIndex,
                                   final Callback callback) {
        new Thread(() -> {
            try {
                List<RemoteManga.PageInfo> pages = manga.listPages(chapter);
                if (pages == null || pageIndex < 0 || pageIndex >= pages.size()) {
                    throw new IllegalStateException("页码超出范围");
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("无法创建缓存目录");
                }
                RemoteManga.PageInfo p = pages.get(pageIndex);
                File out = cachedFile(dir, pageIndex, p.nameHint);
                if (!isValidCached(out)) {
                    File tmp = new File(out.getAbsolutePath() + ".tmp");
                    if (tmp.exists()) tmp.delete();
                    if (isVideoName(p.nameHint)) {
                        RemoteManga.writeVideoMarker(out, p);
                    } else {
                        manga.fetchPage(p, out);
                    }
                }
                boolean ok = out.exists() && out.length() > 0;
                Log.i(TAG, "fetchSingle " + chapter.name + " #" + pageIndex + " ok=" + ok);
                if (callback != null) {
                    MAIN.post(() -> callback.onResult(ok ? dir : null,
                            ok ? null : "该页下载为空"));
                }
            } catch (Throwable e) {
                Log.w(TAG, "fetchSingle failed: " + e.getMessage());
                if (callback != null) {
                    MAIN.post(() -> callback.onResult(null,
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                }
            }
        }, "remote-page-fetch").start();
    }

    /** Re-lists a chapter and pulls missing pages near the front, so a
     *  truncated page list ("最后几页打不开") can recover. */
    public static void refreshPageList(final RemoteManga manga,
                                       final RemoteManga.ChapterInfo chapter,
                                       final File dir, final Callback callback) {
        new Thread(() -> {
            int added = 0;
            try {
                List<RemoteManga.PageInfo> pages = manga.listPages(chapter);
                if (pages == null || pages.isEmpty()) {
                    throw new IllegalStateException("刷新页面列表为空");
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("无法创建缓存目录");
                }
                // A short burst is enough to expose a truncated head; the
                // bulk downloader keeps filling the rest in the background.
                int n = Math.min(pages.size(), 12);
                for (int i = 0; i < n; i++) {
                    RemoteManga.PageInfo p = pages.get(i);
                    File out = cachedFile(dir, i, p.nameHint);
                    if (isValidCached(out)) continue;
                    if (isVideoName(p.nameHint)) {
                        RemoteManga.writeVideoMarker(out, p);
                    } else {
                        manga.fetchPage(p, out);
                    }
                    if (out.exists() && out.length() > 0) added++;
                }
                Log.i(TAG, "refreshPageList " + chapter.name + " added=" + added);
                if (callback != null) MAIN.post(() -> callback.onResult(dir, null));
            } catch (Throwable e) {
                Log.w(TAG, "refreshPageList failed: " + e.getMessage());
                if (callback != null) MAIN.post(() -> callback.onResult(null, e.getMessage()));
            }
        }, "remote-list-refresh").start();
    }

    /** Warms up only the first [maxPages] pages (sequential, silent). */
    public static void prefetch(final RemoteManga manga,
                                final RemoteManga.ChapterInfo chapter,
                                final File dir, final int maxPages) {
        // Never prefetch a chapter that is actively downloading: two threads
        // writing the same page's .tmp is exactly what causes "下载为空".
        if (ACTIVE.containsKey(dir.getAbsolutePath())) {
            Log.i(TAG, "prefetch skipped (active) " + dir.getAbsolutePath());
            return;
        }
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
                    manga.fetchPage(p, out);
                    if (!out.exists() || out.length() == 0L) continue;
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
            long cap = MangaSettings.get(context).integer(MangaSettings.IMAGE_CACHE_MB)
                    * 1024L * 1024L;
            // Evict down to the user's configured cap. The ONLY hard protection
            // is the current chapter (skipped by absolute path below); a fixed
            // multi-GB floor made the setting meaningless - users saw 3.6 GB
            // cached with a 512 MB limit ("1g+3g"). Keep a small 256 MB slack
            // so we don't thrash on every page, but honour the setting.
            long floor = Math.max(256L * 1024L * 1024L, cap * 4L / 5L);
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
            // Single-file viewer cache lives in a SIBLING directory and used to
            // be ignored entirely (piled up 500+ MB, breaking the user's cap).
            // Include its folders as eviction candidates now.
            File singleRoot = new File(root.getParentFile(), "remote-single");
            File[] singles = singleRoot.listFiles();
            if (singles != null) {
                for (File s : singles) if (s.isDirectory()) chapters.add(s);
            }
            // Clean .tmp leftovers first (never readable, always count against cap).
            for (File ch : chapters) {
                File[] fs = ch.listFiles();
                if (fs == null) continue;
                for (File f : fs) if (f.isFile() && f.getName().endsWith(".tmp")) f.delete();
            }
            long total = 0L;
            for (File ch : chapters) total += dirSize(ch);
            if (total <= floor) return;
            chapters.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            String protect = protectDir == null ? "" : protectDir.getAbsolutePath();
            for (File ch : chapters) {
                if (total <= floor) break;
                if (ch.getAbsolutePath().equals(protect)) continue;
                long s = dirSize(ch);
                // File.delete() fails on a NON-EMPTY directory - that bug let
                // the cache grow to 3 GB. Delete the files inside first.
                if (deleteRecursively(ch)) {
                    total -= s;
                    Log.i(TAG, "evicted " + ch.getAbsolutePath() + " (" + s + " bytes)");
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "enforceCacheCap failed: " + e.getMessage());
        }
    }

    /** Deletes a directory and everything under it (File.delete() alone
     *  cannot remove a non-empty dir - the cause of the unbounded cache). */
    private static boolean deleteRecursively(File dir) {
        if (dir == null) return false;
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isDirectory()) deleteRecursively(f);
                else f.delete();
            }
        }
        return dir.delete();
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

    /** File name layout shared by the bulk downloader and single-page
     *  top-ups: the reader's page list is built from these files, so both
     *  paths MUST agree or a fetched page never appears in the list. */
    public static File cachedFile(File dir, int index, String nameHint) {
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
