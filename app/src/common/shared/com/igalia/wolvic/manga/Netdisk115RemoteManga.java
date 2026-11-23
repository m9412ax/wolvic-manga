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

    public Netdisk115RemoteManga(String cookie, String cid, String title, String[] media) {
        this(cookie, cid, title, media, new String[0], 0, true);
    }

    public Netdisk115RemoteManga(String cookie, String cid, String title, String[] media,
                                 String[] videoExt, int sortMode, boolean videoFirst) {
        this.cookie = cookie;
        this.cid = (cid == null || cid.isEmpty()) ? "0" : cid;
        this.title = (title == null || title.isEmpty()) ? "115 网盘" : title;
        this.media = media;
        this.videoExt = videoExt == null ? new String[0] : videoExt;
        this.sortMode = sortMode;
        this.videoFirst = videoFirst;
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
        List<Manga115Api.DirEntry> mediaEntries = new ArrayList<>();
        for (Manga115Api.DirEntry e : entries) {
            if (!e.isDir && isMedia(e.name)
                    && (!e.pickCode.isEmpty() || !e.fileId.isEmpty())) {
                mediaEntries.add(e);
            }
        }
        // Same order as the file browser (video-first + shared name/date sort).
        mediaEntries.sort(SortUtil.listingComparator(sortMode, videoFirst,
                e -> false,
                e -> MangaSettings.hasExtension(e.name, videoExt),
                e -> e.name,
                e -> e.modifiedMs));
        List<PageInfo> out = new ArrayList<>();
        for (int i = 0; i < mediaEntries.size(); i++) {
            Manga115Api.DirEntry e = mediaEntries.get(i);
            String id = !e.pickCode.isEmpty() ? e.pickCode : e.fileId;
            out.add(new PageInfo(i, id, e.name, e.size, e.modifiedMs));
        }
        android.util.Log.i(TAG, "listPages " + chapter.name + " (cid=" + chapter.id + ") -> "
                + out.size());
        return out;
    }

    @Override
    public void fetchPage(PageInfo page, File dest) throws Exception {
        byte[] bytes = Manga115Api.imageBytes(cookie, page.id);
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            out.write(bytes);
        }
    }

    @Override
    public MediaDataSource openVideo(PageInfo page) {
        // 8 MB x2 prefetch slots: the feeder issues large Range GETs in the
        // background, the decoder reads from memory (no per-read HTTP request).
        return new PrefetchVideoDataSource(
                new HttpVideoDataSource(cookie, page.id), 8 * 1024 * 1024);
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
