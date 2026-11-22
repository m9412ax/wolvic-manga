/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.media.MediaDataSource;
import android.util.Log;

import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.util.EnumSet;

/**
 * Lazy MediaDataSource for an SMB video with a SLIDING READ-AHEAD WINDOW.
 *
 * MediaPlayer asks for small chunks very frequently. Doing one smbj SMB2 READ
 * per chunk is a network round-trip each time, which on VR WiFi shows up as a
 * stutter every 2-3 s (the decoder drains its tiny buffer before the next
 * round-trip lands). This class fills a contiguous window (configurable via
 * VIDEO_BUFFER_MB, default 4 MB) in 256 KB SMB reads and serves all in-window
 * readAt() calls from memory - one network trip per window, not per chunk.
 * Seeks simply re-anchor the window, so fast-forward stays smooth.
 *
 * Construction does NO network; the connection opens on first getSize/readAt.
 */
public final class SmbVideoDataSource extends MediaDataSource {

    private static final String TAG = "SmbVideoDS";
    private static final int SMB_CHUNK = 256 * 1024; // stay under negotiated max read size

    private final MangaSmb.Auth auth;
    private final String path;
    private final int windowSize;
    private long size = -1L;

    private SMBClient client;
    private Connection connection;
    private Session session;
    private DiskShare share;
    private com.hierynomus.smbj.share.File file;

    // Sliding read-ahead window.
    private byte[] window;
    private long windowStart = -1L;
    private int windowLen = 0;

    private static final java.util.Set<AccessMask> READ_MASK = EnumSet.of(
            AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES,
            AccessMask.FILE_READ_EA, AccessMask.SYNCHRONIZE);
    private static final java.util.Set<SMB2ShareAccess> SHARE_ACCESS = EnumSet.of(
            SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE);
    private static final java.util.Set<FileAttributes> FILE_ATTRS = EnumSet.of(
            FileAttributes.FILE_ATTRIBUTE_NORMAL);

    public SmbVideoDataSource(MangaSmb.Auth auth, String path) {
        this(auth, path, 4 * 1024 * 1024);
    }

    public SmbVideoDataSource(MangaSmb.Auth auth, String path, int windowSize) {
        this.auth = auth;
        this.path = path;
        this.windowSize = Math.max(256 * 1024, windowSize);
        this.window = new byte[this.windowSize];
    }

    private synchronized boolean ensureOpen() {
        if (file != null) return true;
        try {
            client = new SMBClient();
            connection = client.connect(auth.host);
            session = connection.authenticate(new AuthenticationContext(
                    auth.user, auth.pass == null ? new char[0] : auth.pass.toCharArray(),
                    auth.domain == null ? "" : auth.domain));
            share = (DiskShare) session.connectShare(auth.share);
            String p = path == null ? "" : path.replace('\\', '/');
            while (p.startsWith("/")) p = p.substring(1);
            size = share.getFileInformation(p).getStandardInformation().getEndOfFile();
            file = share.openFile(p, READ_MASK, FILE_ATTRS, SHARE_ACCESS,
                    SMB2CreateDisposition.FILE_OPEN, null);
            Log.i(TAG, "opened " + path + " size=" + size + " window=" + windowSize);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "open failed " + path + ": " + e.getMessage(), e);
            closeQuietly();
            return false;
        }
    }

    /** Fills the read-ahead window starting at [position] using several SMB
     *  reads of SMB_CHUNK so no single request exceeds the negotiated limit. */
    private void fillWindow(long position) {
        windowStart = position;
        windowLen = 0;
        if (size >= 0 && position >= size) return;
        long available = (size >= 0) ? (size - position) : windowSize;
        int want = (int) Math.min(windowSize, available);
        int filled = 0;
        while (filled < want) {
            int chunk = Math.min(SMB_CHUNK, want - filled);
            int n = file.read(window, position + filled, filled, chunk);
            if (n <= 0) break;
            filled += n;
        }
        windowLen = filled;
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int length) {
        if (!ensureOpen()) return -1;
        if (length <= 0) return 0;
        // Reuse the window when the requested position still lies inside it.
        if (windowStart < 0 || position < windowStart || position >= windowStart + windowLen) {
            fillWindow(position);
        }
        if (windowLen <= 0) return -1;
        int inWindow = (int) Math.min(length, windowLen - (position - windowStart));
        if (inWindow <= 0) return -1;
        System.arraycopy(window, (int) (position - windowStart), buffer, offset, inWindow);
        return inWindow;
    }

    @Override
    public synchronized long getSize() {
        if (!ensureOpen()) return -1L;
        return size;
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try { if (file != null) file.close(); } catch (Throwable ignored) { }
        file = null;
        try { if (share != null) share.close(); } catch (Throwable ignored) { }
        share = null;
        try { if (session != null) session.close(); } catch (Throwable ignored) { }
        session = null;
        try { if (connection != null) connection.close(); } catch (Throwable ignored) { }
        connection = null;
        try { if (client != null) client.close(); } catch (Throwable ignored) { }
        client = null;
        window = null;
    }
}
