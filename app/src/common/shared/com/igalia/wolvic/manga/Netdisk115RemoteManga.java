/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.media.MediaDataSource;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A 115 netdisk folder read on demand as a manga: sub-folders = chapters.
 *  Works with loose image files (jpg/png/...) and videos, both via the web
 *  pickcode direct link. Whole-zip manga are not supported by this path. */
public final class Netdisk115RemoteManga implements RemoteManga {

    private static final String TAG = "Netdisk115Remote";

    private final String cookie;
    private final String cid;
    private final String title;
    private final String[] media;
    private final String[] videoExt;
    private final int sortMode;
    private final boolean videoFirst;
    private final int videoBufferBytes;

    public Netdisk115RemoteManga(String cookie, String cid, String title, String[] media) {
        this(cookie, cid, title, media, new String[0], 0, true, 64 * 1024 * 1024);
    }

    public Netdisk115RemoteManga(String cookie, String cid, String title, String[] media,
                                 String[] videoExt, int sortMode, boolean videoFirst) {
        this(cookie, cid, title, media, videoExt, sortMode, videoFirst, 64 * 1024 * 1024);
    }

    public Netdisk115RemoteManga(String cookie, String cid, String title, String[] media,
                                 String[] videoExt, int sortMode, boolean videoFirst,
                                 int videoBufferBytes) {
        this.cookie = cookie;
        this.cid = (cid == null || cid.isEmpty()) ? "0" : cid;
        this.title = (title == null || title.isEmpty()) ? "115 网盘" : title;
        this.media = media;
        this.videoExt = videoExt == null ? new String[0] : videoExt;
        this.sortMode = sortMode;
        this.videoFirst = videoFirst;
        this.videoBufferBytes = videoBufferBytes;
    }

    @Override
    public String key() { return "115:" + cid; }

    @Override
    public String title() { return title; }

    @Override
    public List<ChapterInfo> listChapters() throws Exception {
        List<Manga115Api.DirEntry> entries = Manga115Api.listDir(cookie, cid);
        List<ChapterInfo> out = new ArrayList<>();

        // The folder the user is standing in may hold loose images directly.
        // Add it as the FIRST chapter so "read this directory" always shows
        // exactly the pictures the file browser just displayed.
        boolean hasDirectMedia = false;
        for (Manga115Api.DirEntry e : entries) {
            if (!e.isDir && isMedia(e.name)) { hasDirectMedia = true; break; }
        }
        if (hasDirectMedia) {
            out.add(new ChapterInfo(cid, title));
        }

        for (Manga115Api.DirEntry e : entries) {
            if (e.isDir) {
                // A folder's own id is file_id in the long schema (e.cid is the
                // PARENT id in that schema); fall back to cid for the short one.
                String childId = !e.fileId.isEmpty() ? e.fileId : e.cid;
                out.add(new ChapterInfo(childId, e.name));
            }
        }
        // No sub-folders and no loose media: still expose this folder so the
        // reader can list it (and report an accurate empty-chapter error).
        if (out.isEmpty()) {
            out.add(new ChapterInfo(cid, title));
        }
        out.sort((a, b) -> NaturalOrder.compare(a.name, b.name));
        return out;
    }

    @Override
    public List<PageInfo> listPages(ChapterInfo chapter) throws Exception {
        List<Manga115Api.DirEntry> entries = Manga115Api.listDir(cookie, chapter.id);
        List<MangaFileCore.Entry> mediaEntries = new ArrayList<>();
        for (Manga115Api.DirEntry e : entries) {
            if (!e.isDir && isMedia(e.name)
                    && (!e.pickCode.isEmpty() || !e.fileId.isEmpty())) {
                String id = !e.pickCode.isEmpty() ? e.pickCode : e.fileId;
                mediaEntries.add(new MangaFileCore.Entry(e.name, id, false, e.size, e.modifiedMs));
            }
        }
        // Shared file core: same order as the 115 browser (video-first + the
        // one name/date sort mode all three sources use).
        mediaEntries = MangaFileCore.sortForListing(mediaEntries, sortMode, videoFirst, videoExt);
        List<PageInfo> out = new ArrayList<>();
        for (int i = 0; i < mediaEntries.size(); i++) {
            MangaFileCore.Entry e = mediaEntries.get(i);
            out.add(new PageInfo(i, e.id, e.name, e.size, e.modifiedMs));
        }
        android.util.Log.i(TAG, "listPages " + chapter.name + " (cid=" + chapter.id + ") -> "
                + out.size());
        return out;
    }

    @Override
    public void fetchPage(PageInfo page, File dest) throws Exception {
        Manga115Api.downloadToFile(cookie, page.id, dest, null);
    }

    @Override
    public void fetchPage(PageInfo page, File dest, ProgressListener listener) throws Exception {
        Manga115Api.downloadToFile(cookie, page.id, dest, listener);
    }

    @Override
    public MediaDataSource openVideo(PageInfo page) {
        // Two prefetch slots from the shared video-buffer setting (same as
        // SMB). 8 MB was far too small for a ~3 GB / high-bitrate video: the
        // feeder could not refill before the decoder drained a slot, producing
        // a stall every few seconds.
        return new PrefetchVideoDataSource(
                new HttpVideoDataSource(cookie, page.id), videoBufferBytes);
    }

    @Override
    public PrefetchVideoDataSource.Reader openVideoReader(PageInfo page) {
        return new HttpVideoDataSource(cookie, page.id);
    }

    @Override
    public VideoStream openVideoStream(PageInfo page) {
        // 115 CDN speaks plain HTTP Range, so ExoPlayer's DefaultHttpDataSource
        // does the transport (connection reuse, redirects, retries). Only the
        // short-lived CDN cookie/UA need to be injected.
        Manga115Api.ResolvedLink link = Manga115Api.resolveLink(cookie, page.id);
        if (link == null || link.url == null || link.url.isEmpty()) {
            return null;
        }
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        String cdnCookie = link.cdnCookie == null ? "" : link.cdnCookie.trim();
        if (!cdnCookie.isEmpty()) {
            headers.put("Cookie", cdnCookie);
        } else if (cookie != null && !cookie.isEmpty()) {
            headers.put("Cookie", cookie);
        }
        headers.put("Referer", "https://115.com/");
        headers.put("User-Agent", "Mozilla/5.0 115Browser/27.0.5.7");
        return new VideoStream(link.url, headers, null);
    }

    private boolean isMedia(String name) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : media) {
            if (e.equalsIgnoreCase(ext)) return true;
        }
        return false;
    }
}
