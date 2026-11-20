/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import java.io.File;
import java.util.List;

/**
 * Abstraction for an ONLINE manga folder that is read page-by-page with
 * caching (as opposed to downloading the whole thing up front). Both SMB
 * shares and 115 netdisk folders implement this. All methods BLOCK and must
 * be called from a background thread.
 *
 * The reader drives it through MangaChapterSession.remote() +
 * RemoteChapterLoader, which reuse the exact same layered/concurrent,
 * "first page shows immediately" pipeline as the extension sources.
 */
public interface RemoteManga {

    /** Stable identity used for cache dirs and bookmarks. */
    String key();

    /** Human readable manga title. */
    String title();

    /** Ordered chapters (sub-folders that contain images/videos). */
    List<ChapterInfo> listChapters() throws Exception;

    /** Ordered pages of a chapter. imageUrl hint is used only for the file
     *  extension; the actual bytes come from fetchPage(). */
    List<PageInfo> listPages(ChapterInfo chapter) throws Exception;

    /** Downloads one page's bytes into dest (a .tmp file; caller renames).
     *  Must be safe to call concurrently from the download worker pool. */
    void fetchPage(PageInfo page, File dest) throws Exception;

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
        public final String id;       // stable remote id/path of the image
        public final String nameHint; // file name, used to derive extension
        public PageInfo(int index, String id, String nameHint) {
            this.index = index;
            this.id = id;
            this.nameHint = nameHint;
        }
    }
}
