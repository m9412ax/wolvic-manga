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
         *  while a chapter is still downloading. curName/curBytes give the
         *  file currently downloading for a detailed progress line. */
        default void onProgress(int done, int total, String curName,
                                long curDone, long curTotal) { }
        /** Back-compatible overload. */
        default void onProgress(int done, int total) {
            onProgress(done, total, null, -1L, -1L);
        }
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

        /**
         * Fetches ONE page of a chapter on demand. Used when the bulk download
         * gave up on a page (its retries failed) and the reader is now sitting
         * on it: better one targeted retry than a page that never opens.
         */
        default void fetchPage(int index, int pageIndex, DirCallback callback) {
            if (callback != null) {
                callback.onDir(null, "该来源不支持按页补下载");
            }
        }

        /**
         * Re-fetches a chapter's page list (bypassing the short-lived cache)
         * and pulls whatever pages are still missing. Used when a chapter ends
         * a few pages early, which usually means the first list was truncated.
         */
        default void refreshPageList(int index, DirCallback callback) {
            if (callback != null) {
                callback.onDir(null, "该来源不支持刷新列表");
            }
        }
    }

    public final List<File> dirs;
    public final int index;
    public final int page;
    /** Fraction of the current page height (continuous mode); -1 = page bottom. */
    public final float frac;
    public final String startFilePath;
    public final String progressKey;
    public final ChapterProvider provider;
    /** Canonical key of every chapter (see MangaReadingState). */
    public final List<String> chapterKeys;
    /** Source id of every chapter (SMB path / 115 folder id), for deletion. */
    public final List<String> chapterIds;
    /** Non-null for SMB/115 online sessions; used by the reader to stream
     *  video marker files instead of decoding them as images. */
    public final RemoteManga remoteManga;

    private MangaChapterSession(List<File> dirs, int index, int page, float frac,
                                String startFilePath, String progressKey,
                                ChapterProvider provider, List<String> chapterKeys,
                                List<String> chapterIds) {
        this(dirs, index, page, frac, startFilePath, progressKey, provider, null,
                chapterKeys, chapterIds);
    }

    private MangaChapterSession(List<File> dirs, int index, int page, float frac,
                                String startFilePath, String progressKey,
                                ChapterProvider provider, RemoteManga remoteManga,
                                List<String> chapterKeys, List<String> chapterIds) {
        this.dirs = dirs;
        this.index = index;
        this.page = page;
        this.frac = frac;
        this.startFilePath = startFilePath;
        this.progressKey = progressKey;
        this.provider = provider;
        this.remoteManga = remoteManga;
        this.chapterKeys = chapterKeys == null ? new ArrayList<String>() : chapterKeys;
        this.chapterIds = chapterIds == null ? new ArrayList<String>() : chapterIds;
    }

    /** Canonical key of the chapter at [i], or null when unknown. */
    public String chapterKeyAt(int i) {
        if (chapterKeys == null || i < 0 || i >= chapterKeys.size()) {
            return null;
        }
        return chapterKeys.get(i);
    }

    public static MangaChapterSession local(File root, List<File> chapters, int index,
                                            int page, float frac, String startFilePath) {
        List<String> keys = new ArrayList<>();
        for (File chapter : chapters) {
            keys.add(chapter == null ? "" : chapter.getAbsolutePath());
        }
        return new MangaChapterSession(chapters, index, page, frac, startFilePath,
                "local:" + root.getAbsolutePath(), null, keys, null);
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
                // Disk preload is CAPACITY based: keep warming the neighbour
                // chapter until the user's 硬盘上限 is reached instead of a
                // fixed 3 pages. 120 pages is a sanity cap so one huge chapter
                // cannot monopolise the connection.
                MangaSourceRepository.INSTANCE.prefetchChapter(context, source, manga,
                        chapters.get(i), Math.min(120,
                                RemoteChapterLoader.pagesByBudget(context, 0L)));
            }

            @Override
            public void fetchPage(int i, int pageIndex, DirCallback callback) {
                if (i < 0 || i >= chapters.size()) {
                    if (callback != null) {
                        callback.onDir(null, "章节序号越界");
                    }
                    return;
                }
                MangaSourceRepository.INSTANCE.fetchSinglePage(context, source, manga,
                        chapters.get(i), pageIndex,
                        (dir, error) -> {
                            if (callback != null) {
                                callback.onDir(dir, error);
                            }
                        });
            }

            @Override
            public void refreshPageList(int i, DirCallback callback) {
                if (i < 0 || i >= chapters.size()) {
                    if (callback != null) {
                        callback.onDir(null, "章节序号越界");
                    }
                    return;
                }
                MangaSourceRepository.INSTANCE.refreshChapter(context, source, manga,
                        chapters.get(i),
                        (dir, error) -> {
                            if (callback != null) {
                                callback.onDir(dir, error);
                            }
                        });
            }
        };
        List<String> keys = new ArrayList<>();
        for (SChapter chapter : chapters) {
            keys.add("src:" + source.getId() + ":" + manga.getUrl() + "|" + chapter.getUrl());
        }
        List<String> ids = new ArrayList<>();
        for (SChapter chapter : chapters) {
            ids.add(chapter.getUrl());
        }
        return new MangaChapterSession(dirs, index, page, frac, null,
                "src:" + source.getId() + ":" + manga.getUrl(), provider, keys, ids);
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
                // Capacity based (SMB / 115): fill up to the disk budget.
                RemoteChapterLoader.prefetch(manga, chapters.get(i), dirs.get(i),
                        Math.min(120, RemoteChapterLoader.pagesByBudget(context, 0L)));
            }

            @Override
            public void fetchPage(int i, int pageIndex, DirCallback callback) {
                if (i < 0 || i >= chapters.size()) {
                    if (callback != null) callback.onDir(null, "章节序号越界");
                    return;
                }
                // Download ONLY this page into the same cache layout the bulk
                // loader uses, so the reader's refreshed file list contains it
                // at the exact position the user jumped to.
                RemoteChapterLoader.fetchSingle(context, manga, chapters.get(i),
                        dirs.get(i), pageIndex,
                        (dir, error) -> { if (callback != null) callback.onDir(dir, error); });
            }

            @Override
            public void refreshPageList(int i, DirCallback callback) {
                if (i < 0 || i >= chapters.size()) {
                    if (callback != null) callback.onDir(null, "章节序号越界");
                    return;
                }
                RemoteChapterLoader.refreshPageList(manga, chapters.get(i), dirs.get(i),
                        (dir, error) -> { if (callback != null) callback.onDir(dir, error); });
            }
        };
        List<String> keys = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (RemoteManga.ChapterInfo chapter : chapters) {
            keys.add(manga.chapterKey(chapter));
            ids.add(chapter.id);
        }
        return new MangaChapterSession(dirs, index, page, frac, null,
                "remote:" + manga.key(), provider, manga, keys, ids);
    }
}