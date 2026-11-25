/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified file core shared by the LOCAL / SMB / 115 sources.
 *
 * Every source normalises its native listing (java.io.File, MangaSmb.Entry,
 * Manga115Api.DirEntry) into {@link Entry} and then runs the SAME ordering
 * rule: directories first, videos before images when enabled, then the shared
 * name/date sort. This is what keeps the three browsers and the reader's page
 * order identical instead of each tab having its own comparator.
 */
public final class MangaFileCore {

    private MangaFileCore() {}

    public static final class Entry {
        public final String name;      // display name
        public final String id;        // path (local/SMB) or pickCode/fileId (115)
        public final boolean directory;
        public final long size;        // -1 when unknown
        public final long modifiedMs;  // 0 when unknown

        public Entry(String name, String id, boolean directory, long size, long modifiedMs) {
            this.name = name == null ? "" : name;
            this.id = id == null ? "" : id;
            this.directory = directory;
            this.size = size;
            this.modifiedMs = modifiedMs;
        }
    }

    /** Directories first, then videos (optional), then the shared sort mode. */
    public static List<Entry> sortForListing(List<Entry> entries, int sortMode,
                                             boolean videoFirst, String[] videoExt) {
        List<Entry> out = new ArrayList<>(entries);
        out.sort(SortUtil.listingComparator(sortMode, videoFirst,
                e -> e.directory,
                e -> !e.directory && MangaSettings.hasExtension(e.name, videoExt),
                e -> e.name,
                e -> e.modifiedMs,
                e -> Math.max(0, e.size)));
        return out;
    }

    /** Media files only (images + videos), in the shared listing order. */
    public static List<Entry> mediaInListingOrder(List<Entry> entries, String[] mediaExt,
                                                  int sortMode, boolean videoFirst,
                                                  String[] videoExt) {
        List<Entry> media = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.directory && MangaSettings.hasExtension(e.name, mediaExt)) {
                media.add(e);
            }
        }
        return sortForListing(media, sortMode, videoFirst, videoExt);
    }
}
