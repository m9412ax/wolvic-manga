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
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimal SMB2/3 client for the "SMB 共享" manga page. Uses smbj (pure
 * SMB2/3, no SMB1). Every call opens a fresh connection/session/share and
 * closes it in finally - smbj objects are NOT shared across IO threads.
 * Call all methods from a background thread.
 */
public final class MangaSmb {

    private static final String TAG = "MangaSmb";

    public static final class Entry {
        public final String path;      // path relative to the share root, '/' separated
        public final String name;
        public final boolean directory;
        public final long size;

        Entry(String path, String name, boolean directory, long size) {
            this.path = path;
            this.name = name;
            this.directory = directory;
            this.size = size;
        }
    }

    public interface ProgressListener {
        void onProgress(long written, long total);
    }

    /** Credentials + target, created fresh per operation. */
    public static final class Auth {
        public final String host;
        public final String share;
        public final String domain;
        public final String user;
        public final String pass;

        public Auth(String host, String share, String domain, String user, String pass) {
            this.host = host;
            this.share = share;
            this.domain = domain;
            this.user = user;
            this.pass = pass;
        }
    }

    private MangaSmb() {}

    private static final Set<AccessMask> READ_MASK = EnumSet.of(
            AccessMask.FILE_READ_DATA,
            AccessMask.FILE_READ_ATTRIBUTES,
            AccessMask.FILE_READ_EA,
            AccessMask.SYNCHRONIZE);
    private static final Set<AccessMask> LIST_MASK = EnumSet.of(
            AccessMask.FILE_LIST_DIRECTORY,
            AccessMask.FILE_READ_ATTRIBUTES,
            AccessMask.SYNCHRONIZE);
    private static final Set<SMB2ShareAccess> SHARE_ACCESS = EnumSet.of(
            SMB2ShareAccess.FILE_SHARE_READ,
            SMB2ShareAccess.FILE_SHARE_WRITE);
    private static final Set<FileAttributes> FILE_ATTRS = EnumSet.of(
            FileAttributes.FILE_ATTRIBUTE_NORMAL);

    /** Lists direct children of [relativePath] inside the share root. */
    public static List<Entry> list(Auth auth, String relativePath) throws Exception {
        SMBClient client = new SMBClient();
        Connection connection = null;
        try {
            connection = client.connect(auth.host);
            Session session = connection.authenticate(new AuthenticationContext(
                    auth.user, auth.pass == null ? new char[0] : auth.pass.toCharArray(),
                    auth.domain == null ? "" : auth.domain));
            DiskShare share = (DiskShare) session.connectShare(auth.share);
            String path = normalizePath(relativePath);
            List<Entry> result = new ArrayList<>();
            for (FileIdBothDirectoryInformation info : share.list(path)) {
                String name = info.getFileName();
                if (name == null || name.equals(".") || name.equals("..")) {
                    continue;
                }
                boolean dir = (info.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                String child = join(path, name);
                result.add(new Entry(child, name, dir, info.getEndOfFile()));
            }
            result.sort((a, b) -> {
                if (a.directory != b.directory) return a.directory ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            });
            Log.i(TAG, "list " + auth.host + "/" + auth.share + "/" + path
                    + " -> " + result.size() + " entries");
            return result;
        } finally {
            closeQuietly(connection);
            closeQuietly(client);
        }
    }

    /** Streams one SMB file to a local destination. */
    public static long download(Auth auth, String relativePath, java.io.File destination,
                                ProgressListener listener) throws Exception {
        SMBClient client = new SMBClient();
        Connection connection = null;
        try {
            connection = client.connect(auth.host);
            Session session = connection.authenticate(new AuthenticationContext(
                    auth.user, auth.pass == null ? new char[0] : auth.pass.toCharArray(),
                    auth.domain == null ? "" : auth.domain));
            DiskShare share = (DiskShare) session.connectShare(auth.share);
            String path = normalizePath(relativePath);
            java.io.File parent = destination.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            final long total = share.getFileInformation(path)
                    .getStandardInformation().getEndOfFile();
            final long[] written = {0};
            java.io.File tmp = new java.io.File(destination.getAbsolutePath() + ".tmp");
            try (File remote = share.openFile(path, READ_MASK, FILE_ATTRS, SHARE_ACCESS,
                         SMB2CreateDisposition.FILE_OPEN, null);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), 16384)) {
                remote.read(out, (num, totalBytes) -> {
                    written[0] = num;
                    if (listener != null) {
                        listener.onProgress(num, totalBytes);
                    }
                });
            }
            if (tmp.length() == 0L) {
                throw new IllegalStateException("SMB 下载为空: " + path);
            }
            // Replace target atomically-ish.
            if (destination.exists() && !destination.delete()) {
                Log.w(TAG, "could not delete old destination " + destination);
            }
            if (!tmp.renameTo(destination)) {
                throw new IllegalStateException("SMB 重命名失败: " + path);
            }
            Log.i(TAG, "download " + path + " -> " + written[0] + " bytes");
            return written[0];
        } finally {
            closeQuietly(connection);
            closeQuietly(client);
        }
    }

    /** Verifies connectivity by listing the share root. */
    public static void test(Auth auth) throws Exception {
        list(auth, "");
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace('\\', '/').trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String join(String base, String name) {
        String b = normalizePath(base);
        return b.isEmpty() ? name : b + "/" + name;
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                Log.w(TAG, "close connection: " + e.getMessage());
            }
        }
    }

    private static void closeQuietly(SMBClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                Log.w(TAG, "close client: " + e.getMessage());
            }
        }
    }
}
