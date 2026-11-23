/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

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
 * Blocking SMB byte reader for PrefetchVideoDataSource. Holds one long-lived
 * smbj connection; readAt() is called ONLY from the prefetch feeder thread, so
 * the decoder thread never waits on the network.
 */
public final class SmbVideoDataSource implements PrefetchVideoDataSource.Reader {

    private static final String TAG = "SmbVideoDS";
    private static final int SMB_CHUNK = 256 * 1024;

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
            close();
            return false;
        }
    }

    @Override
    public long size() {
        if (!ensureOpen()) return -1L;
        return size;
    }

    @Override
    public synchronized int readAt(long position, byte[] buf, int off, int len) {
        if (!ensureOpen() || len <= 0) return -1;
        if (position >= size) return -1;
        int want = (int) Math.min(len, size - position);
        int filled = 0;
        while (filled < want) {
            int chunk = Math.min(SMB_CHUNK, want - filled);
            int n = file.read(buf, position + filled, off + filled, chunk);
            if (n <= 0) break;
            filled += n;
        }
        return filled > 0 ? filled : -1;
    }

    @Override
    public synchronized void close() {
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
