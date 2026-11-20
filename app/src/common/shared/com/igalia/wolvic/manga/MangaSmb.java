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

    /** Enumerates ALL shares on a host via jcifs-ng (smbj has no NetShareEnum).
     *  Returns share names; the caller fills SMB_SHARE with the chosen one.
     *  Uses SMB2 by default so it works on hosts that disabled SMB1. */
    public static List<String> listShares(Auth auth) throws Exception {
        java.util.Properties props = new java.util.Properties();
        // Let jcifs negotiate the best dialect the host supports (SMB1 for old
        // NAS shares enumeration, SMB2/3 for modern hosts).
        jcifs.config.PropertyConfiguration config =
                new jcifs.config.PropertyConfiguration(props);
        jcifs.context.BaseContext base = new jcifs.context.BaseContext(config);
        jcifs.CIFSContext ctx = base.withCredentials(
                new jcifs.smb.NtlmPasswordAuthenticator(
                        auth.domain == null ? "" : auth.domain,
                        auth.user == null ? "" : auth.user,
                        auth.pass == null ? "" : auth.pass));
        String url = "smb://" + auth.host + "/";
        jcifs.smb.SmbFile root = new jcifs.smb.SmbFile(url, ctx);
        jcifs.smb.SmbFile[] shares = root.listFiles();
        List<String> result = new ArrayList<>();
        if (shares != null) {
            for (jcifs.smb.SmbFile s : shares) {
                String name = s.getName();
                // Hide administrative/system shares and the IPC$ pipe.
                if (name == null || name.endsWith("$")) {
                    continue;
                }
                try {
                    if (s.isDirectory()) {
                        result.add(name);
                    }
                } catch (Exception ignored) {
                    result.add(name);
                }
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        Log.i(TAG, "listShares " + auth.host + " -> " + result.size() + " shares");
        return result;
    }

    /**
     * Mirrors an SMB directory tree into a local folder so the existing local
     * reader can open it as a manga: the root folder is the manga, each direct
     * child folder that contains media becomes a chapter, image/video files
     * become pages. Incremental: a local file whose size matches the remote one
     * is skipped, so re-opening a manga only fetches new pages.
     *
     * @param auth             credentials + host + share
     * @param remoteRoot       path of the manga folder, relative to share root
     * @param localRoot        destination local directory (the "manga" folder)
     * @param mediaExtensions  accepted file extensions (without dot, lower-case)
     * @param listener         optional aggregate progress (done pages / total pages)
     * @return number of chapter folders that actually contain media
     */
    public static int mirrorManga(Auth auth, String remoteRoot, java.io.File localRoot,
                                  String[] mediaExtensions, ProgressListener listener) throws Exception {
        SMBClient client = new SMBClient();
        Connection connection = null;
        try {
            connection = client.connect(auth.host);
            Session session = connection.authenticate(new AuthenticationContext(
                    auth.user, auth.pass == null ? new char[0] : auth.pass.toCharArray(),
                    auth.domain == null ? "" : auth.domain));
            DiskShare share = (DiskShare) session.connectShare(auth.share);
            String root = normalizePath(remoteRoot);
            if (!localRoot.exists() && !localRoot.mkdirs()) {
                throw new IllegalStateException("无法创建目录: " + localRoot.getAbsolutePath());
            }

            // Collect direct child folders of the manga root = candidate chapters.
            // Also support a "flat" manga whose root itself holds the images.
            List<Entry> children = listIn(share, root);
            List<String> chapterPaths = new ArrayList<>();
            for (Entry e : children) {
                if (e.directory) {
                    chapterPaths.add(e.path);
                }
            }

            // Count total downloadable pages first (for aggregate progress).
            final int[] total = {0};
            final int[] done = {0};
            int chaptersWithMedia = 0;

            if (chapterPaths.isEmpty()) {
                // Flat manga: put pages directly under localRoot.
                chaptersWithMedia = mirrorFolder(share, root, localRoot,
                        mediaExtensions, listener, total, done) > 0 ? 1 : 0;
            } else {
                for (String chapterPath : chapterPaths) {
                    String chapterName = chapterPath.substring(chapterPath.lastIndexOf('/') + 1);
                    java.io.File chapterDir = new java.io.File(localRoot, safeName(chapterName));
                    int pages = mirrorFolder(share, chapterPath, chapterDir,
                            mediaExtensions, listener, total, done);
                    if (pages > 0) {
                        chaptersWithMedia++;
                    }
                }
            }
            Log.i(TAG, "mirrorManga " + root + " -> " + localRoot
                    + " chapters=" + chaptersWithMedia + " pages=" + done[0]);
            return chaptersWithMedia;
        } finally {
            closeQuietly(connection);
            closeQuietly(client);
        }
    }

    /** Mirrors one folder's media files into localDir. Returns pages present. */
    private static int mirrorFolder(DiskShare share, String remotePath, java.io.File localDir,
                                    String[] mediaExtensions, ProgressListener listener,
                                    int[] total, int[] done) throws Exception {
        if (!localDir.exists() && !localDir.mkdirs()) {
            throw new IllegalStateException("无法创建目录: " + localDir.getAbsolutePath());
        }
        List<Entry> entries = listIn(share, remotePath);
        int pages = 0;
        for (Entry e : entries) {
            if (e.directory || !isMedia(e.name, mediaExtensions)) {
                continue;
            }
            total[0]++;
            java.io.File dest = new java.io.File(localDir, safeName(e.name));
            if (dest.exists() && dest.length() == e.size && e.size > 0) {
                // Already cached and size matches: skip the network round-trip.
                done[0]++;
                if (listener != null) listener.onProgress(done[0], total[0]);
                pages++;
                continue;
            }
            downloadFrom(share, e.path, dest);
            done[0]++;
            if (listener != null) listener.onProgress(done[0], total[0]);
            pages++;
        }
        return pages;
    }

    /** Lists direct children of an already-open share (no new connection). */
    private static List<Entry> listIn(DiskShare share, String path) {
        String p = normalizePath(path);
        List<Entry> result = new ArrayList<>();
        for (FileIdBothDirectoryInformation info : share.list(p)) {
            String name = info.getFileName();
            if (name == null || name.equals(".") || name.equals("..")) continue;
            boolean dir = (info.getFileAttributes()
                    & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
            result.add(new Entry(join(p, name), name, dir, info.getEndOfFile()));
        }
        result.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return result;
    }

    /** Streams a file from an already-open share to a local destination. */
    private static void downloadFrom(DiskShare share, String path, java.io.File destination)
            throws Exception {
        java.io.File parent = destination.getParentFile();
        if (parent != null) parent.mkdirs();
        java.io.File tmp = new java.io.File(destination.getAbsolutePath() + ".tmp");
        try (File remote = share.openFile(normalizePath(path), READ_MASK, FILE_ATTRS,
                     SHARE_ACCESS, SMB2CreateDisposition.FILE_OPEN, null);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), 16384)) {
            remote.read(out);
        }
        if (tmp.length() == 0L) throw new IllegalStateException("SMB 下载为空: " + path);
        if (destination.exists() && !destination.delete()) {
            Log.w(TAG, "could not delete old " + destination);
        }
        if (!tmp.renameTo(destination)) throw new IllegalStateException("重命名失败: " + path);
    }

    private static boolean isMedia(String name, String[] exts) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        for (String e : exts) {
            if (e.equalsIgnoreCase(ext)) return true;
        }
        return false;
    }

    /** Removes characters that are illegal in Android/file-system names. */
    private static String safeName(String name) {
        if (name == null) return "unnamed";
        return name.replaceAll("[\\\\/:*?\"<>|\u0000-\u001F]", "_").trim();
    }

    /** Public wrapper used by the UI to build a stable local manga folder name. */
    public static String safePublicName(String name) {
        return safeName(name);
    }

    /** Public media filter for remote (on-demand) reading. */
    public static boolean isMediaFile(String name, String[] exts) {
        return isMedia(name, exts);
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
