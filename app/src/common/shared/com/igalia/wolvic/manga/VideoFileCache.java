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

    /** Tracks which 256 KB chunks hold REAL bytes (vs pre-allocated zeros).
     *  A 3 GB movie needs only ~12 K bits = 1.5 KB. Used to delay a seek until
     *  the target region is actually on disk, otherwise MediaPlayer reads the
     *  zero-filled preallocation -> black screen with audio only. */
    private final java.util.BitSet filled = new java.util.BitSet();
    private static final int READY_AHEAD_CHUNKS = 3; // ~768 KB must be present
    private volatile long totalBytes = -1L;
    private final long prefetchBytes;

    public VideoFileCache(PrefetchVideoDataSource.Reader reader, File dest,
                          long size, Callback callback) {
        this(reader, dest, size, callback, 12L * 1024 * 1024);
    }

    /** [prefetchBytes] = how many bytes of the head to have on disk before
     *  playback starts (user setting "视频预缓冲"). Bigger = smoother start but
     *  longer wait; smaller = faster start but more likely to stall. */
    public VideoFileCache(PrefetchVideoDataSource.Reader reader, File dest,
                          long size, Callback callback, long prefetchBytes) {
        this.reader = reader;
        this.dest = dest;
        this.size = size;
        this.callback = callback;
        this.prefetchBytes = Math.max(2L * 1024 * 1024,
                Math.min(64L * 1024 * 1024, prefetchBytes));
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
        // Re-anchor whenever the target is past the downloaded frontier, so the
        // zero hole at the seek point gets filled first. Backward seeks into
        // already-downloaded bytes need no action.
        if (p > downloaded - CHUNK) {
            seekAnchor = Math.max(0, p - CHUNK);
        }
    }

    public void cancel() {
        cancelled = true;
        seekAnchor = -1L;
        try { reader.close(); } catch (Throwable ignored) { }
    }

    /** Head threshold: enough to prepare() and play a few seconds. */
    private long headBytes(long total) {
        long h = Math.min(prefetchBytes, total / 10);
        return Math.max(h, 512L * 1024);
    }

    /** A real media container has a non-zero magic in its first bytes
     *  (ftyp for MP4/MOV, EBML header for MKV/WEBM, RIFF for AVI...).
     *  An all-zero head is the signature of the old broken cache. */
    private boolean hasValidHeader(RandomAccessFile raf) {
        try {
            byte[] head = new byte[16];
            raf.seek(0);
            int n = raf.read(head);
            if (n < 4) return false;
            int nonZero = 0;
            for (byte b : head) if (b != 0) nonZero++;
            if (nonZero == 0) return false;
            // Optional sanity: known container magics.
            boolean ftyp = n >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
            boolean ebml = head[0] == (byte) 0x1A && head[1] == (byte) 0x45
                    && head[2] == (byte) 0xDF && head[3] == (byte) 0xA3;
            boolean riff = head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F';
            return ftyp || ebml || riff || nonZero >= 8;
        } catch (Throwable e) {
            return false;
        }
    }

    private void run() {
        RandomAccessFile raf = null;
        try {
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            long remoteSize = reader.size();
            long total = size > 0 ? size : remoteSize;
            if (total <= 0) throw new IllegalStateException("视频大小未知");
            totalBytes = total;

            raf = new RandomAccessFile(dest, "rw");
            // Capture length BEFORE any setLength(). The old build read
            // dest.length() AFTER setLength(total), so it always saw "complete"
            // and skipped the loop -> full-size ALL-ZERO file -> error what=1.
            long existing = dest.length();
            if (existing == total && hasValidHeader(raf)) {
                downloaded = total;
                filled.set(0, (int) ((total - 1) / CHUNK) + 1); // all chunks real
                signalReady();
                safeProgress(total, total);
                Log.i(TAG, "reuse complete cache " + dest.getName());
                return;
            }
            // Anything else (short / corrupt / all-zero full-size) -> fresh copy.
            if (existing > 0) {
                Log.w(TAG, "discard cache " + dest.getName() + " (" + existing
                        + " bytes, total=" + total + ", valid=" + (existing == total && hasValidHeader(raf)) + ")");
            }
            raf.setLength(0L);
            // Pre-allocate to the full size: MediaPlayer needs to seek within a
            // file whose length already equals the real movie duration. Unwritten
            // regions stay zero until the sequential fill reaches them.
            raf.setLength(total);
            downloaded = 0L;
            Log.i(TAG, "cache start " + dest.getName() + " total=" + total);

            byte[] buf = new byte[CHUNK];
            long headTarget = headBytes(total);
            long tailBytes = Math.min(4L * 1024 * 1024, total / 10);
            long tailStart = Math.max(headTarget, total - tailBytes);

            // Phase 1: the HEAD. This is where ftyp/moov normally lives; a real
            // container header must be on disk before prepare() can succeed.
            long headDone = fillRange(raf, reader, buf, 0L, headTarget, total);
            if (cancelled) return;

            // Phase 2: the TAIL. Some records put moov at the END (non-faststart);
            // downloading only the head would make prepare() fail with what=1.
            if (tailStart < total) {
                fillRange(raf, reader, buf, tailStart, total, total);
                if (cancelled) return;
            }

            // Head + tail are now real bytes -> safe to hand to MediaPlayer.
            downloaded = headDone;
            Log.i(TAG, "head+tail ready " + dest.getName()
                    + " head=" + headDone + " tailStart=" + tailStart);
            signalReady();

            // Phase 3: fill the middle sequentially. A seek re-anchors the cursor
            // so the requested region is downloaded first.
            long cursor = headTarget;
            while (!cancelled && cursor < tailStart) {
                Long anchor = seekAnchor;
                if (anchor != null && anchor >= 0 && anchor < total) {
                    seekAnchor = -1L;
                    if (anchor > cursor + CHUNK && anchor < tailStart) {
                        cursor = anchor;
                        Log.i(TAG, "seek re-anchor to " + cursor);
                    }
                }
                int want = (int) Math.min(CHUNK, tailStart - cursor);
                int n = readInto(reader, cursor, buf, want);
                if (n <= 0) { sleep(200); continue; }
                writeAt(raf, cursor, buf, n);
                cursor += n;
                downloaded = Math.max(downloaded, cursor);
                safeProgress(downloaded, total);
            }
            if (!cancelled) {
                downloaded = total;
                signalReady();
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

    /** Fills [from, to) contiguously. Used for head and tail where seek
     *  re-anchoring must NOT skip bytes. Returns the final cursor. */
    private long fillRange(RandomAccessFile raf, PrefetchVideoDataSource.Reader reader,
                           byte[] buf, long from, long to, long total) throws java.io.IOException {
        long cursor = from;
        int stalls = 0;
        while (!cancelled && cursor < to) {
            int want = (int) Math.min(CHUNK, to - cursor);
            int n = readInto(reader, cursor, buf, want);
            if (n <= 0) {
                if (++stalls > 40) throw new IllegalStateException("读取视频数据连续失败");
                sleep(200);
                continue;
            }
            stalls = 0;
            writeAt(raf, cursor, buf, n);
            cursor += n;
            safeProgress(Math.max(downloaded, cursor), total);
        }
        return cursor;
    }

    private int readInto(PrefetchVideoDataSource.Reader r, long pos, byte[] buf, int len) {
        try {
            return r.readAt(pos, buf, 0, len);
        } catch (Throwable e) {
            if (cancelled) return -1;
            Log.w(TAG, "readAt " + pos + ": " + e.getMessage());
            return -1;
        }
    }

    private void writeAt(RandomAccessFile raf, long pos, byte[] buf, int n) throws java.io.IOException {
        synchronized (this) {
            raf.seek(pos);
            raf.write(buf, 0, n);
            int first = (int) (pos / CHUNK);
            int last = (int) ((pos + n - 1) / CHUNK);
            filled.set(first, last + 1);
        }
    }

    /** True when [pos, pos + ~2 MB) holds real downloaded bytes. Public so the
     *  reader can postpone MediaPlayer.seekTo until the target is playable. */
    public boolean regionReady(long pos) {
        if (pos < 0 || totalBytes <= 0) return true; // unknown size: don't block seek
        long limit = Math.min(pos + (long) READY_AHEAD_CHUNKS * CHUNK, totalBytes);
        synchronized (this) {
            long p = pos;
            while (p < limit) {
                int bit = (int) (p / CHUNK);
                if (!filled.get(bit)) return false;
                p = (bit + 1L) * CHUNK;
            }
        }
        return true;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
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
