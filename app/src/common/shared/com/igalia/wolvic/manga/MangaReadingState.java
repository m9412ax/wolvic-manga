/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Read state shared by the reader and the file browsers.
 *
 * Every "chapter" is identified by a canonical key:
 *
 * <pre>
 *   local manga   -> /storage/emulated/0/Manga/Title/Chapter 03
 *   SMB chapter   -> smb://192.168.1.10/share/Manga/Title/Chapter 03
 *   115 chapter   -> 115:&lt;folder-id&gt;
 * </pre>
 *
 * The key is exactly the {@code RemoteManga#key()} of that folder, so the
 * browser and the reader can derive the same value without talking to each
 * other. For a chapter we keep the highest page index the user has reached plus
 * the page count, which is enough to answer both "still reading" and "finished"
 * (last page reached) and to light up the individual files before that page.
 *
 * The same store also keeps the recent-manga list shown on the 历史 page. Those
 * records are purely a view cache: deleting one never touches files or the
 * per-chapter progress above.
 *
 * Persistence is two tab/newline separated blobs in one SharedPreferences file;
 * the state is small (text only) and survives process death without a JSON
 * dependency.
 */
public final class MangaReadingState {

    /** No border at all. */
    public static final int NONE = 0;
    /** Partly read: blue frame. */
    public static final int READING = 1;
    /** Last page reached: green frame. */
    public static final int DONE = 2;

    private static final String PREFS = "manga_reading_state";
    private static final String KEY_CHAPTERS = "chapters";
    private static final String KEY_HISTORY = "history";
    /** History is imported in full from NeeView (12k+ rows); keep enough for
     *  every chapter the user has ever opened instead of truncating silently. */
    private static final int MAX_CHAPTERS = 16000;
    private static final int MAX_HISTORY = 150;

    private static MangaReadingState sInstance;

    public static synchronized MangaReadingState get(Context context) {
        if (sInstance == null) {
            Context app = context.getApplicationContext();
            sInstance = new MangaReadingState(app != null ? app : context);
        }
        return sInstance;
    }

    /** One chapter's read state. */
    public static final class Chapter {
        public final String key;
        /** Highest page index (0 based) the user has reached. */
        public int page;
        /** Page count of that chapter; 0 when not known yet. */
        public int total;
        public long ts;

        Chapter(String key) {
            this.key = key;
        }

        public boolean isDone() {
            return total > 0 && page >= total - 1;
        }
    }

    /** One row of the 历史 page. */
    public static final class Record {
        public String id = "";
        public String title = "";
        /** LOCAL / SMB / 115 / EXT. */
        public String source = "";
        /** Folder path for local, shelf key for remote sources. */
        public String path = "";
        /** Canonical key of the chapter last opened. */
        public String chapterKey = "";
        public String coverPath = "";
        public int index;
        public int page;
        public int total;
        public long ts;

        public int percent() {
            if (total <= 1) {
                return page > 0 ? 100 : 0;
            }
            int p = Math.round((page + 1) * 100f / total);
            return Math.max(0, Math.min(100, p));
        }
    }

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final LinkedHashMap<String, Chapter> mChapters = new LinkedHashMap<>();
    private final List<Record> mHistory = new ArrayList<>();
    private boolean mLoaded;

    private MangaReadingState(Context context) {
        mContext = context;
        mPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------- chapters

    /** True when the store has any record inside [prefix] (a folder). */
    public boolean hasAnyUnder(String prefix) {
        load();
        if (TextUtils.isEmpty(prefix)) {
            return false;
        }
        String pre = prefix.endsWith("/") ? prefix : prefix + "/";
        for (String key : mChapters.keySet()) {
            if (key.equals(prefix) || key.startsWith(pre)) {
                return true;
            }
        }
        return false;
    }

    /** Records the highest page reached in a chapter. Runs on the UI thread. */
    public void noteChapter(String key, int page, int total) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        load();
        Chapter chapter = mChapters.get(key);
        if (chapter == null) {
            chapter = new Chapter(key);
            mChapters.put(key, chapter);
        }
        boolean changed = false;
        if (page > chapter.page) {
            chapter.page = page;
            changed = true;
        }
        if (total > chapter.total) {
            chapter.total = total;
            changed = true;
        }
        chapter.ts = System.currentTimeMillis();
        if (changed || mChapters.size() > MAX_CHAPTERS) {
            saveChapters();
        }
    }

    /** Marks a whole chapter as finished without opening it. */
    public void markDone(String key, int total) {
        noteChapter(key, Math.max(0, total - 1), total);
    }

    public Chapter chapter(String key) {
        if (TextUtils.isEmpty(key)) {
            return null;
        }
        load();
        Chapter c = mChapters.get(key);
        if (c != null) {
            return c;
        }
        // Fall back to a normalised form so stale bad keys still resolve.
        String fixed = MangaSync.repairSmbKey(key, MangaSettings.get(mContext));
        return fixed.equals(key) ? null : mChapters.get(fixed);
    }

    public int stateOf(String key) {
        Chapter chapter = chapter(key);
        if (chapter == null) {
            return NONE;
        }
        return chapter.isDone() ? DONE : READING;
    }

    /**
     * Aggregate state of a folder: DONE only when every chapter inside it has
     * been finished, READING as soon as one of them was opened.
     */
    public int stateOfFolder(String prefix) {
        if (TextUtils.isEmpty(prefix)) {
            return NONE;
        }
        load();
        int seen = 0;
        boolean allDone = true;
        for (Chapter chapter : mChapters.values()) {
            if (!underPrefix(chapter.key, prefix)) {
                continue;
            }
            seen++;
            if (!chapter.isDone()) {
                allDone = false;
            }
        }
        if (seen == 0) {
            return NONE;
        }
        return allDone ? DONE : READING;
    }

    /**
     * Aggregate progress of a whole manga (a local folder, or a remote book
     * whose chapters are keyed {@code <bookKey>|<chapterId>}).
     */
    public static final class Progress {
        /** 0..100, or -1 when nothing has been read yet. */
        public int percent = -1;
        /** Chapters with a known page count. */
        public int chapters;
        /** Chapters finished (last page reached). */
        public int doneChapters;
        public int pages;
        public int readPages;
    }

    /** True when [key] belongs to the book/folder [prefix]. */
    public static boolean underPrefix(String key, String prefix) {
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(prefix)) {
            return false;
        }
        if (key.equals(prefix)) {
            return true;
        }
        return key.startsWith(prefix + "/") || key.startsWith(prefix + "|");
    }

    /** Sums the pages of every chapter inside [prefix] into one percentage. */
    public Progress progressOfFolder(String prefix) {
        Progress p = new Progress();
        if (TextUtils.isEmpty(prefix)) {
            return p;
        }
        load();
        for (Chapter chapter : mChapters.values()) {
            if (!underPrefix(chapter.key, prefix)) {
                continue;
            }
            if (chapter.total > 0) {
                p.pages += chapter.total;
                p.readPages += Math.max(1, Math.min(chapter.page + 1, chapter.total));
                p.chapters++;
                if (chapter.isDone()) {
                    p.doneChapters++;
                }
            } else if (chapter.page > 0) {
                // Page count unknown (still downloading): count it as started.
                p.chapters++;
            }
        }
        if (p.pages > 0) {
            p.percent = Math.max(0, Math.min(100,
                    Math.round(p.readPages * 100f / p.pages)));
        } else if (p.chapters > 0) {
            p.percent = 1;
        }
        return p;
    }

    /** Snapshot of every chapter mark, used by the cross-device sync export. */
    public List<Chapter> allChapters() {
        load();
        List<Chapter> out = new ArrayList<>();
        for (Chapter c : mChapters.values()) {
            Chapter copy = new Chapter(c.key);
            copy.page = c.page;
            copy.total = c.total;
            copy.ts = c.ts;
            out.add(copy);
        }
        return out;
    }

    /**
     * Merges a remote chapter mark. The newer timestamp wins; the page index
     * never moves backwards when both sides agree on the page count.
     * @return true when local state actually changed.
     */
    /** Bulk variant of {@link #importChapter}: merges many marks but persists
     *  only once, so importing a 12k-row history does not write the whole TSV
     *  blob 12k times. */
    public int importChapters(List<Chapter> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return 0;
        }
        load();
        int changed = 0;
        for (Chapter in : incoming) {
            if (in == null || TextUtils.isEmpty(in.key)) {
                continue;
            }
            Chapter local = mChapters.get(in.key);
            if (local == null) {
                mChapters.put(in.key, in);
                changed++;
            } else if (in.ts > local.ts) {
                int newPage = local.total == in.total
                        ? Math.max(local.page, in.page) : Math.max(0, in.page);
                if (newPage != local.page || in.total != local.total) {
                    changed++;
                }
                local.page = newPage;
                local.total = Math.max(0, in.total);
                local.ts = in.ts;
            }
        }
        if (changed > 0) {
            saveChapters();
        }
        return changed;
    }

    public boolean importChapter(String key, int page, int total, long ts) {
        if (TextUtils.isEmpty(key)) {
            return false;
        }
        load();
        Chapter local = mChapters.get(key);
        if (local == null) {
            local = new Chapter(key);
            mChapters.put(key, local);
            local.page = Math.max(0, page);
            local.total = Math.max(0, total);
            local.ts = ts > 0 ? ts : System.currentTimeMillis();
            saveChapters();
            return true;
        }
        if (ts <= local.ts) {
            return false;
        }
        int newPage = local.total == total
                ? Math.max(local.page, page) : Math.max(0, page);
        boolean changed = newPage != local.page || Math.max(0, total) != local.total;
        local.page = newPage;
        local.total = Math.max(0, total);
        local.ts = ts;
        if (changed) {
            saveChapters();
        }
        return changed;
    }

    /** Merges one remote 历史 row; newer timestamp wins. @return true if changed */
    public boolean importRecord(Record incoming) {
        if (incoming == null || TextUtils.isEmpty(incoming.id)) {
            return false;
        }
        load();
        for (int i = 0; i < mHistory.size(); i++) {
            Record local = mHistory.get(i);
            if (!local.id.equals(incoming.id)) {
                continue;
            }
            if (incoming.ts <= local.ts) {
                return false;
            }
            Record copy = new Record();
            copy.id = incoming.id;
            copy.title = incoming.title;
            copy.source = incoming.source;
            copy.path = incoming.path;
            copy.chapterKey = incoming.chapterKey;
            copy.index = incoming.index;
            copy.page = incoming.page;
            copy.total = incoming.total;
            copy.ts = incoming.ts;
            copy.coverPath = incoming.coverPath;
            mHistory.remove(i);
            mHistory.add(0, copy);
            saveHistory();
            return true;
        }
        Record copy = new Record();
        copy.id = incoming.id;
        copy.title = incoming.title;
        copy.source = incoming.source;
        copy.path = incoming.path;
        copy.chapterKey = incoming.chapterKey;
        copy.index = incoming.index;
        copy.page = incoming.page;
        copy.total = incoming.total;
        copy.ts = incoming.ts;
        copy.coverPath = incoming.coverPath;
        mHistory.add(0, copy);
        while (mHistory.size() > MAX_HISTORY) {
            mHistory.remove(mHistory.size() - 1);
        }
        saveHistory();
        return true;
    }

    /** Drops one chapter's progress (file deleted / chapter removed). */
    public void forget(String key) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        load();
        if (mChapters.remove(key) != null) {
            saveChapters();
        }
    }

    /** Drops every chapter under a folder, used when a folder is deleted. */
    public void forgetPrefix(String prefix) {
        if (TextUtils.isEmpty(prefix)) {
            return;
        }
        load();
        String pre = prefix.endsWith("/") ? prefix : prefix + "/";
        boolean changed = false;
        Iterator<String> it = mChapters.keySet().iterator();
        while (it.hasNext()) {
            String key = it.next();
            if (key.equals(prefix) || key.startsWith(pre)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            saveChapters();
        }
    }

    // -------------------------------------------------------------- history

    public void noteRecord(Record record) {
        if (record == null || TextUtils.isEmpty(record.id)) {
            return;
        }
        load();
        record.ts = System.currentTimeMillis();
        Record existing = null;
        for (Record r : mHistory) {
            if (r.id.equals(record.id)) {
                existing = r;
                break;
            }
        }
        if (existing != null) {
            mHistory.remove(existing);
        }
        mHistory.add(0, record);
        while (mHistory.size() > MAX_HISTORY) {
            mHistory.remove(mHistory.size() - 1);
        }
        saveHistory();
    }

    public List<Record> records() {
        load();
        return new ArrayList<>(mHistory);
    }

    public Record record(String id) {
        load();
        for (Record r : mHistory) {
            if (r.id.equals(id)) {
                return r;
            }
        }
        return null;
    }

    /** Removes one row from 历史. Files and progress are left untouched. */
    public void removeRecord(String id) {
        load();
        Record found = null;
        for (Record r : mHistory) {
            if (r.id.equals(id)) {
                found = r;
                break;
            }
        }
        if (found != null) {
            mHistory.remove(found);
            saveHistory();
        }
    }

    public void removeRecords(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        load();
        boolean changed = false;
        for (String id : ids) {
            Iterator<Record> it = mHistory.iterator();
            while (it.hasNext()) {
                if (it.next().id.equals(id)) {
                    it.remove();
                    changed = true;
                }
            }
        }
        if (changed) {
            saveHistory();
        }
    }

    public void clearRecords() {
        load();
        if (!mHistory.isEmpty()) {
            mHistory.clear();
            saveHistory();
        }
    }

    // ---------------------------------------------------------- persistence

    private void load() {
        if (mLoaded) {
            return;
        }
        mLoaded = true;
        final MangaSettings settings = MangaSettings.get(mContext);
        String raw = mPrefs.getString(KEY_CHAPTERS, "");
        if (!TextUtils.isEmpty(raw)) {
            for (String line : raw.split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 4 || parts[0].isEmpty()) {
                    continue;
                }
                try {
                    Chapter chapter = new Chapter(MangaSync.repairSmbKey(parts[0], settings));
                    chapter.page = Integer.parseInt(parts[1]);
                    chapter.total = Integer.parseInt(parts[2]);
                    chapter.ts = Long.parseLong(parts[3]);
                    Chapter old = mChapters.get(chapter.key);
                    // Keep the newer mark when two legacy keys collapse into one.
                    if (old == null || chapter.ts > old.ts) {
                        mChapters.put(chapter.key, chapter);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        String history = mPrefs.getString(KEY_HISTORY, "");
        if (!TextUtils.isEmpty(history)) {
            for (String line : history.split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 9) {
                    continue;
                }
                Record record = new Record();
                record.id = parts[0];
                record.title = parts[1];
                record.source = parts[2];
                record.path = parts[3].startsWith("smb:/")
                        ? MangaSync.repairSmbKey(parts[3], settings) : parts[3];
                record.chapterKey = parts[4].startsWith("smb:/")
                        ? MangaSync.repairSmbKey(parts[4], settings) : parts[4];
                try {
                    record.index = Integer.parseInt(parts[5]);
                    record.page = Integer.parseInt(parts[6]);
                    record.total = Integer.parseInt(parts[7]);
                    record.ts = Long.parseLong(parts[8]);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                record.coverPath = parts.length > 9 ? parts[9] : "";
                if (!record.id.isEmpty()) {
                    mHistory.add(record);
                }
            }
        }
    }

    private void saveChapters() {
        // Evict the oldest entries so a huge library cannot grow the blob
        // without bound; the marks are a convenience, not a source of truth.
        while (mChapters.size() > MAX_CHAPTERS) {
            String oldestKey = null;
            long oldestTs = Long.MAX_VALUE;
            for (Chapter c : mChapters.values()) {
                if (c.ts < oldestTs) {
                    oldestTs = c.ts;
                    oldestKey = c.key;
                }
            }
            if (oldestKey == null) {
                break;
            }
            mChapters.remove(oldestKey);
        }
        StringBuilder sb = new StringBuilder();
        for (Chapter c : mChapters.values()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(c.key.replace('\t', ' ').replace('\n', ' ')).append('\t')
                    .append(c.page).append('\t')
                    .append(c.total).append('\t')
                    .append(c.ts);
        }
        mPrefs.edit().putString(KEY_CHAPTERS, sb.toString()).apply();
    }

    private void saveHistory() {
        StringBuilder sb = new StringBuilder();
        for (Record r : mHistory) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(clean(r.id)).append('\t')
                    .append(clean(r.title)).append('\t')
                    .append(clean(r.source)).append('\t')
                    .append(clean(r.path)).append('\t')
                    .append(clean(r.chapterKey)).append('\t')
                    .append(r.index).append('\t')
                    .append(r.page).append('\t')
                    .append(r.total).append('\t')
                    .append(r.ts).append('\t')
                    .append(clean(r.coverPath));
        }
        mPrefs.edit().putString(KEY_HISTORY, sb.toString()).apply();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ');
    }
}
