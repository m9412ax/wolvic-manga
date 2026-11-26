/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.media.MediaDataSource;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** An SMB folder read on demand as a manga: sub-folders = chapters. */
public final class SmbRemoteManga implements RemoteManga {

    private final MangaSmb.Auth auth;
    private final String remotePath;
    private final String[] media;
    private final String[] videoExt;
    private final int sortMode;
    private final boolean videoFirst;
    private final int videoBufferBytes;
    private final String title;

    public SmbRemoteManga(MangaSmb.Auth auth, String remotePath, String[] media) {
        this(auth, remotePath, media, new String[0], 0, true, 4 * 1024 * 1024);
    }

    public SmbRemoteManga(MangaSmb.Auth auth, String remotePath, String[] media,
                          String[] videoExt, int sortMode, boolean videoFirst,
                          int videoBufferBytes) {
        this.auth = auth;
        this.remotePath = remotePath == null ? "" : remotePath;
        this.media = media;
        this.videoExt = videoExt == null ? new String[0] : videoExt;
        this.sortMode = sortMode;
        this.videoFirst = videoFirst;
        this.videoBufferBytes = videoBufferBytes;
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

    /** Canonical key of a folder on this share, identical to the {@link #key()}
     *  it would get when opened directly. Used for read marks. */
    public static String keyFor(String host, String share, String relativePath) {
        String p = relativePath == null ? "" : relativePath.replace('\\', '/').trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return "smb://" + host + "/" + share + "/" + p;
    }

    @Override
    public String chapterKey(ChapterInfo chapter) {
        return keyFor(auth.host, auth.share, chapter.id);
    }

    @Override
    public boolean deleteChapter(ChapterInfo chapter) throws Exception {
        String path = chapter == null ? null : chapter.id;
        if (path == null || path.trim().isEmpty()) {
            return false;
        }
        java.util.List<String> targets = new ArrayList<>();
        if (path.equals(remotePath)) {
            // This folder itself is the chapter (loose images): delete only the
            // media inside it, never the folder the user is standing in.
            for (PageInfo page : listPages(chapter)) {
                if (page.id != null && !page.id.isEmpty()) {
                    targets.add(page.id);
                }
            }
        } else {
            targets.add(path);
        }
        MangaSmb.delete(auth, targets);
        return true;
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
        List<MangaFileCore.Entry> mediaEntries = new ArrayList<>();
        for (MangaSmb.Entry e : entries) {
            if (!e.directory && MangaSmb.isMediaFile(e.name, media)) {
                mediaEntries.add(new MangaFileCore.Entry(e.name, e.path, false, e.size, e.modifiedMs));
            }
        }
        // Shared file core: identical order to the SMB browser (videos first
        // when enabled, then the same name/date sort mode).
        mediaEntries = MangaFileCore.sortForListing(mediaEntries, sortMode, videoFirst, videoExt);
        List<PageInfo> out = new ArrayList<>();
        for (int i = 0; i < mediaEntries.size(); i++) {
            MangaFileCore.Entry e = mediaEntries.get(i);
            out.add(new PageInfo(i, e.id, e.name, e.size, e.modifiedMs));
        }
        return out;
    }

    @Override
    public void fetchPage(PageInfo page, File dest) throws Exception {
        MangaSmb.download(auth, page.id, dest, null);
    }

    @Override
    public void fetchPage(PageInfo page, File dest, ProgressListener listener) throws Exception {
        MangaSmb.download(auth, page.id, dest,
                listener == null ? null : listener::onProgress);
    }

    @Override
    public MediaDataSource openVideo(PageInfo page) {
        // Two slots of videoBufferBytes each; feeder keeps the next slot full
        // in the background so MediaPlayer's decoder never blocks on SMB I/O.
        return new PrefetchVideoDataSource(
                new SmbVideoDataSource(auth, page.id), videoBufferBytes);
    }

    @Override
    public PrefetchVideoDataSource.Reader openVideoReader(PageInfo page) {
        return new SmbVideoDataSource(auth, page.id);
    }

    @Override
    public VideoStream openVideoStream(PageInfo page) {
        // smbj random-access reader: ExoPlayer can seek anywhere on the share
        // without pre-buffering the whole file. Credentials never leave the
        // SMB client, so nothing is put into a URL.
        String uri = "smb://" + auth.host + "/" + auth.share
                + (page.id == null || page.id.isEmpty() ? "" : "/" + page.id);
        return new VideoStream(uri, null, new SmbVideoDataSource(auth, page.id));
    }

    private static String encodePath(String path) {
        if (path == null) return "";
        String[] parts = path.split("/");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append('/');
            out.append(Uri.encode(part));
        }
        return out.toString();
    }
}
