/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A 115 netdisk folder read on demand as a manga: sub-folders = chapters.
 *  Works with loose image files (jpg/png/...), which use the simple web
 *  pickcode direct link. Whole-zip manga are not supported by this path. */
public final class Netdisk115RemoteManga implements RemoteManga {

    private static final String TAG = "Netdisk115Remote";

    private final String cookie;
    private final String cid;
    private final String title;
    private final String[] media;

    public Netdisk115RemoteManga(String cookie, String cid, String title, String[] media) {
        this.cookie = cookie;
        this.cid = (cid == null || cid.isEmpty()) ? "0" : cid;
        this.title = (title == null || title.isEmpty()) ? "115 网盘" : title;
        this.media = media;
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
                Log.i(TAG, "chapter: " + e.name + " id=" + childId
                        + " (fileId=" + e.fileId + " cid=" + e.cid + ")");
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
        List<PageInfo> out = new ArrayList<>();
        for (Manga115Api.DirEntry e : entries) {
            Log.i(TAG, "entry name=" + e.name + " isDir=" + e.isDir
                    + " fileId=" + e.fileId + " pickCode=" + e.pickCode + " size=" + e.size);
            if (e.isDir || !isMedia(e.name)) continue;
            // Use pickcode when present, otherwise fall back to file_id. Both
            // are accepted by Manga115Api.imageBytes (it tries both endpoints).
            String id = !e.pickCode.isEmpty() ? e.pickCode : e.fileId;
            if (id.isEmpty()) {
                Log.w(TAG, "skip media entry with no id: " + e.name);
                continue;
            }
            out.add(new PageInfo(out.size(), id, e.name));
        }
        out.sort((a, b) -> NaturalOrder.compare(a.nameHint, b.nameHint));
        List<PageInfo> reindexed = new ArrayList<>();
        for (int j = 0; j < out.size(); j++) {
            PageInfo p = out.get(j);
            reindexed.add(new PageInfo(j, p.id, p.nameHint));
        }
        Log.i(TAG, "listPages " + chapter.name + " (cid=" + chapter.id + ") -> "
                + reindexed.size());
        return reindexed;
    }

    @Override
    public void fetchPage(PageInfo page, File dest) throws Exception {
        byte[] bytes = Manga115Api.imageBytes(cookie, page.id);
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(dest)) {
            out.write(bytes);
        }
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
