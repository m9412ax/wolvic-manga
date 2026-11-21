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
 * Lazy MediaDataSource for an SMB video file. Construction does NO network;
 * the SMB connection is opened on the first readAt()/getSize() call. MediaPlayer
 * then pulls byte ranges over SMB2 with seeking/fast-forward, so a video on the
 * LAN starts instantly instead of waiting for the whole file to download.
 */
public final class SmbVideoDataSource extends MediaDataSource {

    private static final String TAG = "SmbVideoDS";

    private final MangaSmb.Auth auth;
    private final String path;
    private long size = -1L;

    private SMBClient client;
    private Connection connection;
    private Session session;
    private DiskShare share;
    private com.hierynomus.smbj.share.File file;

    private static final java.util.Set<AccessMask> READ_MASK = EnumSet.of(
            AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES,
            AccessMask.FILE_READ_EA, AccessMask.SYNCHRONIZE);
    private static final java.util.Set<SMB2ShareAccess> SHARE_ACCESS = EnumSet.of(
            SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE);
    private static final java.util.Set<FileAttributes> FILE_ATTRS = EnumSet.of(
            FileAttributes.FILE_ATTRIBUTE_NORMAL);

    public SmbVideoDataSource(MangaSmb.Auth auth, String path) {
        this.auth = auth;
        this.path = path;
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
            Log.i(TAG, "opened " + path + " size=" + size);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "open failed " + path + ": " + e.getMessage(), e);
            closeQuietly();
            return false;
        }
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int length) {
        if (!ensureOpen()) return -1;
        try {
            // smbj supports random-offset reads: byte[], fileOffset, bufOffset, len
            int n = file.read(buffer, position, offset, length);
            return n; // -1 at EOF is exactly what MediaDataSource expects
        } catch (Throwable e) {
            Log.w(TAG, "readAt pos=" + position + " len=" + length + ": " + e.getMessage());
            return -1;
        }
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
    }
}
