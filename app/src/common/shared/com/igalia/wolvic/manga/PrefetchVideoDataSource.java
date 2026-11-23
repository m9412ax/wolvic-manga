/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.media.MediaDataSource;
import android.util.Log;

/**
 * Two-slot background prefetch wrapper around a blocking remote byte Reader.
 *
 * Why: MediaPlayer calls readAt() synchronously from its decoder thread with
 * tiny chunks. If readAt() touches the network, the decoder buffer drains and
 * playback stutters every few seconds. This class moves ALL network I/O to a
 * dedicated feeder thread which keeps a slot filled ahead of the read cursor;
 * readAt() only does System.arraycopy from a ready in-memory slot.
 *
 * Seeking lands in the other slot (or forces one synchronous fill) so fast
 * forward stays smooth after the first seek.
 */
public final class PrefetchVideoDataSource extends MediaDataSource {

    private static final String TAG = "PrefetchVideoDS";

    /** A blocking byte source (SMB random read or HTTP Range). */
    public interface Reader {
        long size() throws Exception;
        /** Fill buf[off..off+len) from absolute position; return bytes read (<=0 = EOF/error). */
        int readAt(long position, byte[] buf, int off, int len) throws Exception;
        void close();
    }

    private static final class Slot {
        final byte[] data;
        volatile long start = -1L;
        volatile int len = 0;
        volatile boolean ready = false;
        Slot(int n) { data = new byte[n]; }
        boolean contains(long p) { return ready && p >= start && p < start + len; }
        void reset() { ready = false; len = 0; }
    }

    private final Reader reader;
    private final Slot[] slots;
    private volatile int active = 0;
    private volatile long size = -1L;
    private volatile boolean closed = false;
    private Thread feeder;
    private final Object startLock = new Object();

    public PrefetchVideoDataSource(Reader reader, int slotBytes) {
        this.reader = reader;
        int n = Math.max(256 * 1024, slotBytes);
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

    /** Fills the INACTIVE slot ahead of the active slot's end, forever. */
    private void feed() {
        while (!closed) {
            try {
                int inactive = 1 - active;
                Slot s = slots[inactive];
                if (!s.ready && size > 0) {
                    long from;
                    Slot a = slots[active];
                    if (a.ready && a.len > 0) from = a.start + a.len;
                    else if (a.start >= 0) from = a.start;
                    else from = 0L;
                    if (from >= size) {
                        sleep(80);
                        continue;
                    }
                    int want = (int) Math.min(s.data.length, size - from);
                    int n = reader.readAt(from, s.data, 0, want);
                    if (n > 0) {
                        s.start = from;
                        s.len = n;
                        s.ready = true;
                    } else {
                        sleep(80);
                    }
                } else {
                    sleep(20);
                }
            } catch (Throwable t) {
                if (!closed) Log.w(TAG, "feed: " + t.getMessage());
                sleep(80);
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    /** Fill a slot synchronously (only on a seek that misses both slots). */
    private boolean fillNow(int idx, long pos) {
        Slot s = slots[idx];
        s.reset();
        if (size > 0 && pos >= size) return false;
        long cap = (size > 0) ? Math.min(s.data.length, size - pos) : s.data.length;
        try {
            int n = reader.readAt(pos, s.data, 0, (int) cap);
            if (n <= 0) return false;
            s.start = pos;
            s.len = n;
            s.ready = true;
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "fillNow pos=" + pos + ": " + e.getMessage());
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
                a.reset();                 // consumed slot is behind; let feeder refill it
                active = other;
                a = slots[other];
            } else {
                // Seek outside both slots: one synchronous fill, then prefetch resumes.
                a.reset();
                if (!fillNow(active, position)) return -1;
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
