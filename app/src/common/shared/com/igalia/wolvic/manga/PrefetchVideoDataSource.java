/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.media.MediaDataSource;
import android.util.Log;

/**
 * Two-slot incrementally-filled prefetch wrapper around a blocking remote byte
 * Reader.
 *
 * All network I/O happens on a dedicated feeder thread; the MediaPlayer decoder
 * thread only does System.arraycopy from a ready in-memory region.
 *
 * IMPORTANT (root cause of "SMB video never prepared"): a slot is readable as
 * soon as its FIRST bytes land, not only when fully filled. An earlier version
 * required a full slot and did a synchronous whole-slot (64 MB) read on a cache
 * miss - with smbj's 256 KB chunks that is 256 network round-trips on the
 * decoder thread, so prepare() timed out. Now the feeder fills in small chunks
 * and a miss only synchronously reads the bytes actually requested (plus a
 * small read-ahead), so prepare() and seeking return immediately.
 */
public final class PrefetchVideoDataSource extends MediaDataSource {

    private static final String TAG = "PrefetchVideoDS";
    private static final int FEED_CHUNK = 512 * 1024;   // one network read in feeder
    private static final int SYNC_CHUNK = 256 * 1024;   // read-ahead on a decoder miss

    /** A blocking byte source (SMB random read or HTTP Range). */
    public interface Reader {
        long size() throws Exception;
        int readAt(long position, byte[] buf, int off, int len) throws Exception;
        void close();
    }

    /** A slot is valid over [start, start+len) even while still filling. */
    private static final class Slot {
        final byte[] data;
        long start = -1L;
        int len = 0;
        boolean filled = false;
        Slot(int n) { data = new byte[n]; }
        boolean contains(long p) { return start >= 0 && p >= start && p < start + len; }
        void reset() { start = -1L; len = 0; filled = false; }
        int room() { return data.length - len; }
    }

    private final Reader reader;
    private final Slot[] slots;
    private int active = 0;                 // guarded by this
    private volatile long size = -1L;
    private volatile boolean closed = false;
    private Thread feeder;
    private final Object startLock = new Object();

    /** Hard safety cap per slot. Wolvic runs with a 256 MB app heap; two slots
     *  of 64+ MB (or a stale 256 MB setting) caused an instant OutOfMemoryError
     *  on the decoder thread the moment a video was opened. Keep total slot
     *  memory well under the heap so images + decoder still have room. */
    private static final int MAX_SLOT_BYTES = 20 * 1024 * 1024;

    public PrefetchVideoDataSource(Reader reader, int slotBytes) {
        this.reader = reader;
        int n = Math.max(256 * 1024, Math.min(slotBytes, MAX_SLOT_BYTES));
        Log.i(TAG, "slot request=" + slotBytes + " clamped=" + n);
        this.slots = new Slot[]{new Slot(n), new Slot(n)};
    }

    private void ensureStarted() {
        if (feeder != null) return;
        synchronized (startLock) {
            if (feeder != null) return;
            try { size = reader.size(); } catch (Throwable e) { size = -1L; }
            feeder = new Thread(this::feed, "video-prefetch");
            feeder.setDaemon(true);
            feeder.start();
            Log.i(TAG, "feeder started size=" + size + " slot=" + slots[0].data.length);
        }
    }

    /**
     * Keeps the inactive slot filled ahead of the active slot's end. Fills in
     * FEED_CHUNK-sized pieces and publishes partial progress (len) after each
     * piece, so readAt can consume a slot before it is completely filled.
     */
    private void feed() {
        while (!closed) {
            try {
                synchronized (this) {
                    int inactive = 1 - active;
                    Slot s = slots[inactive];
                    Slot a = slots[active];
                    if (!s.filled) {
                        long from;
                        if (s.len > 0) {
                            from = s.start + s.len;          // continue this slot
                        } else if (a.start >= 0 && a.len > 0) {
                            from = a.start + a.len;          // just past active
                        } else if (a.start >= 0) {
                            from = a.start;
                        } else {
                            from = 0L;
                        }
                        if (size > 0 && from >= size) {
                            if (s.len == 0) s.start = from;
                            s.filled = true;
                        } else {
                            int want = Math.min(FEED_CHUNK, s.room());
                            if (size > 0) want = (int) Math.min(want, size - from);
                            if (want > 0) {
                                int n = reader.readAt(from, s.data, s.len, want);
                                if (n > 0) {
                                    if (s.len == 0) s.start = from;
                                    s.len += n;
                                    if (s.room() <= 0 || (size > 0 && s.start + s.len >= size)) {
                                        s.filled = true;
                                    }
                                }
                            } else {
                                s.filled = true;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                if (!closed) Log.w(TAG, "feed: " + t.getMessage());
            }
            sleep(12);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    /** Synchronously fill [idx] from [pos] with ONLY what is needed. Caller
     *  holds synchronized(this). */
    private boolean fillAt(int idx, long pos, int want) {
        Slot s = slots[idx];
        s.reset();
        if (size > 0 && pos >= size) return false;
        int cap = Math.max(want, SYNC_CHUNK);
        if (size > 0) cap = (int) Math.min(cap, size - pos);
        cap = Math.min(cap, s.data.length);
        if (cap <= 0) return false;
        try {
            int n = reader.readAt(pos, s.data, 0, cap);
            if (n <= 0) return false;
            s.start = pos;
            s.len = n;
            if (s.room() <= 0 || (size > 0 && pos + n >= size)) s.filled = true;
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "fillAt pos=" + pos + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int length) {
        if (closed) return -1;
        if (length <= 0) return 0;
        ensureStarted();

        Slot a = slots[active];
        if (!a.contains(position)) {
            int other = 1 - active;
            if (slots[other].contains(position)) {
                active = other;
                a = slots[other];
            } else {
                // Outside both slots: read only the requested bytes (+small
                // read-ahead) instead of a whole slot. This is what keeps
                // prepare() and seek fast on high-latency SMB.
                if (!fillAt(other, position, length)) return -1;
                active = other;
                a = slots[other];
            }
        }
        int avail = (int) (a.start + a.len - position);
        int n = Math.min(length, avail);
        if (n <= 0) return -1;
        System.arraycopy(a.data, (int) (position - a.start), buffer, offset, n);
        return n;
    }

    @Override
    public synchronized long getSize() {
        if (closed) return -1L;
        ensureStarted();
        return size;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try { reader.close(); } catch (Throwable ignored) { }
        for (Slot s : slots) s.reset();
        Log.i(TAG, "closed");
    }
}
