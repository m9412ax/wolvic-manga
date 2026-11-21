/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import eu.kanade.tachiyomi.source.Source;
import eu.kanade.tachiyomi.source.model.SChapter;
import eu.kanade.tachiyomi.source.model.SManga;

/**
 * A manga opened as an ordered chapter playlist. Local manga map one folder
 * per chapter; online sources map one download-cache folder per chapter and
 * fetch missing chapters on demand through the provider.
 */
public class MangaChapterSession {

    public interface DirCallback {
        void onDir(File dir, String error);
        /** Fired as pages land on disk so the reader can grow its page list
         *  while a chapter is still downloading. Default no-op for callers
         *  that do not care about progressive updates. */
        default void onProgress(int done, int total) { }
    }

    public interface ChapterProvider {
        /** Makes sure chapter [index] is fully on disk, then reports its folder.
         *  [startPage] is where the reader will enter, so pages right after it
         *  are prioritised in the download queue. */
        void ensureChapter(int index, int startPage, DirCallback callback);

        /** Back-compatible overload: chapter starts at page 0. */
        default void ensureChapter(int index, DirCallback callback) {
            ensureChapter(index, 0, callback);
        }

        /** Disk-only pre-cache of the chapter head for instant switching. */
        void prefetchChapter(int index);
    }

    public final List<File> dirs;
    public final int index;
    public final int page;
    /** Fraction of the current page height (continuous mode); -1 = page bottom. */
    public final float frac;
    public final String startFilePath;
    public final String progressKey;
    public final ChapterProvider provider;
    /** Non-null for SMB/115 online sessions; used by the reader to stream
     *  video marker files instead of decoding them as images. */
    public final RemoteManga remoteManga;

    private MangaChapterSession(List<File> dirs, int index, int page, float frac,
                                String startFilePath, String progressKey,
                                ChapterProvider provider) {
        this(dirs, index, page, frac, startFilePath, progressKey, provider, null);
    }

    private MangaChapterSession(List<File> dirs, int index, int page, float frac,
                                String startFilePath, String progressKey,
                                ChapterProvider provider, RemoteManga remoteManga) {
        this.dirs = dirs;
        this.index = index;
        this.page = page;
        this.frac = frac;
        this.startFilePath = startFilePath;
        this.progressKey = progressKey;
        this.provider = provider;
        this.remoteManga = remoteManga;
    }

    public static MangaChapterSession local(File root, List<File> chapters, int index,
                                            int page, float frac, String startFilePath) {
        return new MangaChapterSession(chapters, index, page, frac, startFilePath,
                "local:" + root.getAbsolutePath(), null);
    }

    public static MangaChapterSession source(Context context, Source source, SManga manga,
                                             List<SChapter> chapters, int index,
                                             int page, float frac) {
        List<File> dirs = new ArrayList<>();
        for (SChapter chapter : chapters) {
            dirs.add(MangaSourceRepository.INSTANCE.chapterDir(context, source, manga, chapter));
        }
        ChapterProvider provider = new ChapterProvider() {
            @Override
            public void ensureChapter(int i, int startPage, DirCallback callback) {
                if (i < 0 || i >= chapters.size()) {
                    callback.onDir(null, "章节序号越界");
                    return;
                }
                MangaSourceRepository.INSTANCE.downloadChapter(context, source, manga,
                        chapters.get(i), callback::onProgress,
                        (dir, error) -> callback.onDir(dir, error), startPage);
            }

            @Override
            public void prefetchChapter(int i) {
                if (i < 0 || i >= chapters.size()) {
                    return;
                }
                // Only warm a small head. The page LIST is now cached (so the
                // chapter opens with all URLs instantly) and downloadChapter
                // streams the rest on demand. Prefetching 64 full images used
                // to saturate the host connection and make the just-switched
                // chapter's own images crawl. A few pages keeps it cheap.
                MangaSourceRepository.INSTANCE.prefetchChapter(context, source, manga,
                        chapters.get(i), 3);
            }
        };
        return new MangaChapterSession(dirs, index, page, frac, null,
                "src:" + source.getId() + ":" + manga.getUrl(), provider);
    }

    /** Builds a session for an ONLINE remote manga (SMB / 115). Each chapter
     *  maps to a stable local cache folder; pages are fetched on demand through
     *  RemoteChapterLoader with the same layered/concurrent pipeline as source
     *  chapters (first page shows immediately, the rest stream in). */
    public static MangaChapterSession remote(final Context context,
                                             final RemoteManga manga,
                                             final List<RemoteManga.ChapterInfo> chapters,
                                             final List<File> dirs,
                                             int index, int page, float frac) {
        ChapterProvider provider = new ChapterProvider() {
            @Override
            public void ensureChapter(int i, int startPage, DirCallback callback) {
                if (i < 0 || i >= chapters.size()) {
                    callback.onDir(null, "章节序号越界");
                    return;
                }
                RemoteChapterLoader.download(context, manga, chapters.get(i), dirs.get(i),
                        startPage, callback::onProgress,
                        (dir, error) -> callback.onDir(dir, error));
            }

            @Override
            public void prefetchChapter(int i) {
                if (i < 0 || i >= chapters.size()) return;
                RemoteChapterLoader.prefetch(manga, chapters.get(i), dirs.get(i), 3);
            }
        };
        return new MangaChapterSession(dirs, index, page, frac, null,
                "remote:" + manga.key(), provider, manga);
    }
}