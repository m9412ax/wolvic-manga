/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Downloads a remote video (SMB / 115) into a LOCAL file so MediaPlayer can
 * play a plain local path. This is the fix for choppy playback: Android's
 * MediaPlayer + MediaDataSource has tiny, uncontrollable decoder buffering;
 * a local file has the OS page cache + MediaPlayer's full file buffering.
 *
 * The file is pre-allocated to the full size, then filled sequentially from
 * the remote byte Reader. Playback starts after a small head is on disk.
 * Seeking re-anchors the download cursor to the target position.
 */
public final class VideoFileCache {

    private static final String TAG = "VideoFileCache";
    private static final int CHUNK = 256 * 1024;

    public interface Callback {
        /** Enough of the head is on disk to start playback. */
        void onReady(File localFile);
        /** download progress for the overlay: done bytes / total bytes. */
        void onProgress(long done, long total);
        void onError(String message);
    }

    private final PrefetchVideoDataSource.Reader reader;
    private final File dest;
    private final long size;
    private final Callback callback;

    private volatile long downloaded = 0L;
    private volatile long seekAnchor = -1L;   // seek() requests a re-anchor here
    private volatile boolean cancelled = false;
    private volatile boolean signalledReady = false;
    private Thread worker;

    public VideoFileCache(PrefetchVideoDataSource.Reader reader, File dest,
                          long size, Callback callback) {
        this.reader = reader;
        this.dest = dest;
        this.size = size;
        this.callback = callback;
    }

    public long downloaded() { return downloaded; }
    public long size() { return size; }
    public File file() { return dest; }

    public void start() {
        worker = new Thread(this::run, "video-file-cache");
        worker.setDaemon(true);
        worker.start();
    }

    /** Re-anchor sequential download to [position] so a seek target fills first. */
    public void seekTo(long position) {
        if (position < 0 || size <= 0) return;
        long p = Math.min(position, size - 1);
        // Only re-anchor if the target is meaningfully ahead of what we have;
        // seeking backwards into already-downloaded bytes needs no action.
        if (p > downloaded + CHUNK) {
            seekAnchor = p;
        }
    }

    public void cancel() {
        cancelled = true;
        seekAnchor = -1L;
        try { reader.close(); } catch (Throwable ignored) { }
    }

    /** Head threshold: enough to prepare() and play a few seconds. */
    private long headBytes() {
        long h = Math.min(12L * 1024 * 1024, size / 10);
        return Math.max(h, 512L * 1024);
    }

    private void run() {
        RandomAccessFile raf = null;
        try {
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            long remoteSize = reader.size();
            long total = size > 0 ? size : remoteSize;
            if (total <= 0) throw new IllegalStateException("视频大小未知");

            raf = new RandomAccessFile(dest, "rw");
            // If a complete cached copy exists, reuse it.
            if (dest.length() == total) {
                downloaded = total;
                signalReady();
                safeProgress(total, total);
                Log.i(TAG, "reuse complete cache " + dest.getName());
                return;
            }
            // Pre-allocate to full size so MediaPlayer can seek anywhere without
            // hitting EOF. Unfilled regions are zeros until written.
            raf.setLength(total);
            long cursor = Math.min(dest.length(), total);
            downloaded = cursor;
            if (cursor >= headBytes() && !signalledReady) signalReady();

            byte[] buf = new byte[CHUNK];
            while (!cancelled && cursor < total) {
                Long anchor = seekAnchor;
                if (anchor != null && anchor >= 0) {
                    cursor = Math.min(anchor, total - 1);
                    seekAnchor = -1L;
                    Log.i(TAG, "seek re-anchor to " + cursor);
                }
                int want = (int) Math.min(CHUNK, total - cursor);
                int n;
                try {
                    n = reader.readAt(cursor, buf, 0, want);
                } catch (Throwable e) {
                    if (cancelled) return;
                    Log.w(TAG, "readAt " + cursor + ": " + e.getMessage());
                    try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                    continue;
                }
                if (n <= 0) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) { }
                    continue;
                }
                synchronized (this) {
                    raf.seek(cursor);
                    raf.write(buf, 0, n);
                }
                cursor += n;
                downloaded = cursor;
                if (!signalledReady && cursor >= headBytes()) signalReady();
                safeProgress(cursor, total);
            }
            if (!cancelled) {
                safeProgress(total, total);
                Log.i(TAG, "cache complete " + dest.getName());
            }
        } catch (Throwable e) {
            if (!cancelled) {
                Log.e(TAG, "cache failed: " + e.getMessage(), e);
                try { callback.onError(e.getMessage() == null ? "缓存失败" : e.getMessage()); }
                catch (Throwable ignored) { }
            }
        } finally {
            if (raf != null) try { raf.close(); } catch (Throwable ignored) { }
        }
    }

    private void signalReady() {
        if (signalledReady) return;
        signalledReady = true;
        try { callback.onReady(dest); } catch (Throwable ignored) { }
    }

    private void safeProgress(long done, long total) {
        try { callback.onProgress(done, total); } catch (Throwable ignored) { }
    }
}
