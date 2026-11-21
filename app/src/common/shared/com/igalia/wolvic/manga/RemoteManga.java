/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.media.MediaDataSource;

import java.io.File;
import java.util.List;

/**
 * Abstraction for an ONLINE manga folder that is read page-by-page with
 * caching (as opposed to downloading the whole thing up front). Both SMB
 * shares and 115 netdisk folders implement this.
 *
 * RemoteChapterLoader walks this interface sequentially (SMB/115 links have a
 * constant speed, so parallel downloads only create holes), while the reader
 * turns the cached folder into its normal page list. Videos are NOT downloaded
 * in full: the loader writes a small marker file and the reader opens a
 * MediaDataSource that streams the bytes on demand (instant playback on LAN).
 */
public interface RemoteManga {

    /** Stable identity used for cache dirs and bookmarks. */
    String key();

    /** Human readable manga title. */
    String title();

    /** Ordered chapters (sub-folders that contain images/videos). */
    List<ChapterInfo> listChapters() throws Exception;

    /** Ordered pages of a chapter, in the SAME order the file browser shows
     *  them (dirs first is not relevant here; videos first + name/date sort). */
    List<PageInfo> listPages(ChapterInfo chapter) throws Exception;

    /** Downloads one page's bytes into dest (a .tmp file; caller renames).
     *  Called sequentially from the download worker thread. */
    void fetchPage(PageInfo page, File dest) throws Exception;

    /** Opens a video page for ONLINE streaming. The returned MediaDataSource
     *  must be lazy (no network in the constructor) because it is created on
     *  the UI thread; connections are opened inside readAt/getSize. Returns
     *  null when this source cannot stream (reader then plays the cached file). */
    default MediaDataSource openVideo(PageInfo page) { return null; }

    final class ChapterInfo {
        public final String id;     // stable remote id/path
        public final String name;   // display name
        public ChapterInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    final class PageInfo {
        public final int index;
        public final String id;       // stable remote id/path of the media
        public final String nameHint; // file name, used to derive extension
        public final long size;       // remote byte size (-1 if unknown)
        public final long modifiedMs; // for date sorting

        public PageInfo(int index, String id, String nameHint) {
            this(index, id, nameHint, -1L, 0L);
        }
        public PageInfo(int index, String id, String nameHint,
                        long size, long modifiedMs) {
            this.index = index;
            this.id = id;
            this.nameHint = nameHint;
            this.size = size;
            this.modifiedMs = modifiedMs;
        }
    }

    // ---- video marker sidecar -------------------------------------------
    // A remote video page is NOT downloaded in full. The loader writes a small
    // marker file named like a video (0003.mp4) so the reader's normal folder
    // scan sees it; the marker carries everything needed to build a lazy
    // MediaDataSource without another network round trip on the UI thread.
    String VIDEO_MARKER = "REMOTEVIDEO1";
    String VIDEO_MARKER_SEP = "\n";

    static boolean isVideoMarker(File file) {
        if (file == null || !file.isFile()) return false;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8"))) {
            return VIDEO_MARKER.equals(r.readLine());
        } catch (Exception ignored) {
            return false;
        }
    }

    static void writeVideoMarker(File dest, PageInfo page) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        String body = VIDEO_MARKER + VIDEO_MARKER_SEP
                + (page.id == null ? "" : page.id.replace("\n", " ")) + VIDEO_MARKER_SEP
                + (page.nameHint == null ? "" : page.nameHint.replace("\n", " ")) + VIDEO_MARKER_SEP
                + page.size + VIDEO_MARKER_SEP
                + page.modifiedMs;
        try (java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
            out.write(body.getBytes("UTF-8"));
        }
    }

    static PageInfo readVideoMarker(File file) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8"))) {
            if (!VIDEO_MARKER.equals(r.readLine())) return null;
            String id = r.readLine();
            String hint = r.readLine();
            String sizeLine = r.readLine();
            String dateLine = r.readLine();
            long size = -1L, date = 0L;
            try { if (sizeLine != null) size = Long.parseLong(sizeLine); } catch (Exception ignored) { }
            try { if (dateLine != null) date = Long.parseLong(dateLine); } catch (Exception ignored) { }
            return new PageInfo(0, id == null ? "" : id,
                    hint == null ? "video.mp4" : hint, size, date);
        } catch (Exception ignored) {
            return null;
        }
    }
}
