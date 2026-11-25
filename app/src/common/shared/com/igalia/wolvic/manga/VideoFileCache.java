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
    private static final int CHUNK = 1024 * 1024;

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

    private long lastAnchorMs = 0L;

    /** Re-anchor download to [position] and force the target region to be
     *  really downloaded. A sparse/legacy cache can have chunks mis-marked as
     *  filled (scanFilled sees a stray non-zero byte in compressed data), so a
     *  plain seek would jump into a mostly-zero region and the decoder would
     *  stall -- "seek needs a stick nudge to show picture". Clearing the bits
     *  guarantees the target bytes are freshly fetched before we report ready. */
    public void seekTo(long position) {
        seekTo(position, 0L);
    }

    /** Re-anchor and invalidate a longer forward window. Two-phase seeks need
     *  both the preroll point AND the exact target to be freshly downloaded;
     *  clearing only the first 1 MB let the final seek read a stale/zero hole. */
    public void seekTo(long position, long aheadBytes) {
        if (position < 0 || size <= 0) return;
        long p = Math.min(position, size - 1);
        long anchor = Math.max(0, p - CHUNK);
        long defaultAhead = (long) (READY_AHEAD_CHUNKS + 2) * CHUNK;
        long ahead = Math.max(defaultAhead, Math.max(0L, aheadBytes));
        synchronized (this) {
            int from = (int) (anchor / CHUNK);
            int to = (int) (Math.min(size - 1, p + ahead) / CHUNK);
            for (int c = from; c <= to; c++) filled.clear(c);
        }
        seekAnchor = anchor;
        lastAnchorMs = System.currentTimeMillis();
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

    /** MP4 playability gate. A valid ftyp alone is NOT enough: non-faststart
     *  movies keep `moov` at the END. A half-downloaded cache can have a perfect
     *  ftyp head but NO moov anywhere -> MediaPlayer fails with what=1 forever.
     *  Scan the head and tail for the `moov` atom; reject the cache if missing
     *  so it gets re-downloaded (the tail download will then fetch moov). */
    private boolean hasMoov(RandomAccessFile raf, long total) {
        byte[] probe = new byte[256 * 1024];
        byte[] needle = {'m', 'o', 'o', 'v'};
        // head
        try {
            raf.seek(0);
            int n = raf.read(probe);
            if (contains(probe, n, needle)) return true;
        } catch (Throwable ignored) { }
        // tail
        try {
            long tailStart = Math.max(0, total - probe.length);
            raf.seek(tailStart);
            int n = raf.read(probe);
            if (contains(probe, n, needle)) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    /** Scans an existing full-size cache and marks only the chunks that hold
     *  real (non-zero) bytes. Sequential read with a modest buffer so a 3 GB
     *  file takes a few seconds on the worker thread, not a seek per chunk.
     *  Returns the number of real chunks. */
    private int scanFilled(RandomAccessFile raf, long total) {
        filled.clear();
        final int BUF = 64 * 1024;
        byte[] b = new byte[BUF];
        int realChunks = 0;
        try {
            raf.seek(0);
            long pos = 0;
            int chunk = 0;
            while (pos < total && !cancelled) {
                int want = (int) Math.min(BUF, total - pos);
                int n = raf.read(b, 0, want);
                if (n <= 0) break;
                boolean anyNonZero = false;
                for (int i = 0; i < n; i++) {
                    if (b[i] != 0) { anyNonZero = true; break; }
                }
                // A chunk spans many buffer reads; remember if any was non-zero.
                // We finalise a chunk when pos crosses its end.
                if (anyNonZero) {
                    // mark every chunk touched by this buffer (conservative:
                    // better to trust a chunk with one stray byte than to
                    // re-download it needlessly).
                    int first = (int) (pos / CHUNK);
                    int last = (int) ((pos + n - 1) / CHUNK);
                    for (int c = first; c <= last; c++) {
                        if (!filled.get(c)) { filled.set(c); realChunks++; }
                    }
                }
                pos += n;
            }
        } catch (Throwable e) {
            Log.w(TAG, "scanFilled failed: " + e.getMessage());
        }
        return realChunks;
    }

    private boolean isMp4(RandomAccessFile raf) {
        try {
            byte[] h = new byte[8];
            raf.seek(0);
            if (raf.read(h) < 8) return false;
            return h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p';
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean contains(byte[] hay, int len, byte[] needle) {
        if (len < needle.length) return false;
        for (int i = 0; i <= len - needle.length; i++) {
            boolean ok = true;
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) { ok = false; break; }
            }
            if (ok) return true;
        }
        return false;
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
            boolean fullSizeValid = existing == total && hasValidHeader(raf);
            // A full-size MP4 with a valid ftyp can STILL be unplayable when its
            // `moov` atom was never downloaded (non-faststart file, head is just
            // ftyp+free+mdat). Require moov before reusing, otherwise MediaPlayer
            // fails with what=1 and the video can never start.
            if (fullSizeValid && isMp4(raf) && !hasMoov(raf, total)) {
                Log.w(TAG, "discard cache without moov " + dest.getName());
                fullSizeValid = false;
            }
            byte[] buf = new byte[CHUNK];
            long headTarget = headBytes(total);
            long tailBytes = Math.min(4L * 1024 * 1024, total / 10);
            long tailStart = Math.max(headTarget, total - tailBytes);

            if (fullSizeValid) {
                // A full-size cache may be a SPARSE shell: an earlier build wrote
                // only scattered chunks and left the rest as zeros. Marking all
                // chunks ready made seek() jump into a zero hole -> decoder stall
                // ("cannot seek / black"). Scan the real data, then backfill the
                // holes below. signalReady() now because header+moov are present.
                int realChunks = scanFilled(raf, total);
                int allChunks = (int) ((total - 1) / CHUNK) + 1;
                downloaded = 0L;
                signalReady();
                Log.i(TAG, "reuse cache " + dest.getName() + " realChunks="
                        + realChunks + "/" + allChunks);
                if (realChunks >= allChunks) {
                    safeProgress(total, total);
                    return; // genuinely complete
                }
            } else {
                // Fresh download: discard old + pre-allocate full size.
                if (existing > 0) {
                    Log.w(TAG, "discard cache " + dest.getName() + " (" + existing
                            + " bytes, total=" + total + ", valid=" + fullSizeValid + ")");
                }
                raf.setLength(0L);
                raf.setLength(total);
                downloaded = 0L;
                Log.i(TAG, "cache start " + dest.getName() + " total=" + total);

                // Phase 1: HEAD (ftyp/moov normally here).
                fillRange(raf, reader, buf, 0L, headTarget, total);
                if (cancelled) return;
                // Phase 2: TAIL (non-faststart moov).
                if (tailStart < total) {
                    fillRange(raf, reader, buf, tailStart, total, total);
                    if (cancelled) return;
                }
                Log.i(TAG, "head+tail ready " + dest.getName()
                        + " head=" + headTarget + " tailStart=" + tailStart);
                signalReady();
            }

            // Phase 3: backfill every chunk that is still a zero hole. A seek
            // re-anchors the cursor so the requested region downloads first.
            long cursor = 0L;
            while (!cancelled && cursor < total) {
                if (filled.get((int) (cursor / CHUNK))) {
                    cursor += CHUNK;
                    continue;
                }
                Long anchor = seekAnchor;
                if (anchor != null && anchor >= 0 && anchor < total) {
                    seekAnchor = -1L;
                    long a = (anchor / CHUNK) * CHUNK; // align to chunk
                    if (a > cursor && !filled.get((int) (a / CHUNK))) {
                        cursor = a;
                        Log.i(TAG, "seek re-anchor to " + cursor);
                    }
                }
                int want = (int) Math.min(CHUNK, total - cursor);
                int n = readInto(reader, cursor, buf, want);
                if (n <= 0) { sleep(200); continue; }
                writeAt(raf, cursor, buf, n);
                cursor += n;
                downloaded = Math.max(downloaded, cursor);
                // Do NOT report this as overall buffering progress: backfilling
                // a sparse cache walks the whole file and would show a scary
                // "0% -> 100% full download" bar every time the user seeks.
                // The reader shows a local "buffering target" indicator instead.
            }
            if (!cancelled) {
                downloaded = total;
                signalReady();
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
