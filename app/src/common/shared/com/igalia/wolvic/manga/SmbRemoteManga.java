/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** An SMB folder read on demand as a manga: sub-folders = chapters. */
public final class SmbRemoteManga implements RemoteManga {

    private final MangaSmb.Auth auth;
    private final String remotePath;
    private final String[] media;
    private final String title;

    public SmbRemoteManga(MangaSmb.Auth auth, String remotePath, String[] media) {
        this.auth = auth;
        this.remotePath = remotePath == null ? "" : remotePath;
        this.media = media;
        String leaf = this.remotePath.isEmpty() ? auth.share
                : this.remotePath.substring(this.remotePath.lastIndexOf('/') + 1);
        this.title = leaf;
    }

    @Override
    public String key() {
        // Stable across sessions: host/share + remote path.
        return "smb://" + auth.host + "/" + auth.share + "/" + remotePath;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public List<ChapterInfo> listChapters() throws Exception {
        List<MangaSmb.Entry> entries = MangaSmb.list(auth, remotePath);
        List<ChapterInfo> out = new ArrayList<>();
        // Loose media directly in this folder = the FIRST chapter, so opening
        // the current folder reads exactly the pictures the browser displayed.
        boolean hasDirectMedia = false;
        for (MangaSmb.Entry e : entries) {
            if (!e.directory && MangaSmb.isMediaFile(e.name, media)) {
                hasDirectMedia = true;
                break;
            }
        }
        if (hasDirectMedia) {
            out.add(new ChapterInfo(remotePath, title));
        }
        for (MangaSmb.Entry e : entries) {
            if (e.directory) {
                out.add(new ChapterInfo(e.path, e.name));
            }
        }
        // Flat folder with no sub-folders: expose one synthetic chapter.
        if (out.isEmpty()) {
            out.add(new ChapterInfo(remotePath, title));
        }
        out.sort((a, b) -> NaturalOrder.compare(a.name, b.name));
        return out;
    }

    @Override
    public List<PageInfo> listPages(ChapterInfo chapter) throws Exception {
        List<MangaSmb.Entry> entries = MangaSmb.list(auth, chapter.id);
        List<PageInfo> out = new ArrayList<>();
        int i = 0;
        for (MangaSmb.Entry e : entries) {
            if (!e.directory && MangaSmb.isMediaFile(e.name, media)) {
                out.add(new PageInfo(i++, e.path, e.name));
            }
        }
        return out;
    }

    @Override
    public void fetchPage(PageInfo page, File dest) throws Exception {
        MangaSmb.download(auth, page.id, dest, null);
    }
}
