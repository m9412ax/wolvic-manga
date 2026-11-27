/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct NeeView profile sync: reads and writes NeeView's own History.json /
 * Bookmark.json over SMB, so the PC needs no bridge script at all.
 *
 * <p>The NeeView profile lives on share {@code d} on the PC. The manga files
 * themselves live on {@code e} (the SMB share configured in the app), which
 * is why the two auth objects below are deliberately different: changing the
 * manga share must never move the profile sync.</p>
 *
 * <p>Only the first history row is exchanged — the other thousands of rows
 * stay untouched. Bookmarks are merged with the VR shelf: PC's bookmark list
 * becomes the shelf, VR-only entries (115 / extension sources) are preserved.
 * The VR side keeps ALL chapter marks, which is what paints the blue / green
 * frames on folders and files.</p>
 */
public final class MangaNeeViewSync {

    private static final String TAG = "NeeViewSync";
    /** NeeView's profile is on the PC's D: drive. Never follow SMB_SHARE here. */
    private static final String PROFILE_SHARE = "d";
    private static final String HISTORY_FILE = "History.json";
    private static final String BOOKMARK_FILE = "Bookmark.json";
    /** NeeView restores its startup book/page from here, NOT from History.json.
     *  Writing only the history row is why "进度有了但打开位置没变". */
    private static final String USER_SETTING_FILE = "UserSetting.json";
    /** NeeView expects 7 fractional digits and a numeric UTC offset. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSXXX");

    private MangaNeeViewSync() { }

    private static MangaSmb.Auth syncAuth(MangaSettings settings) {
        return new MangaSmb.Auth(
                settings.text(MangaSettings.SMB_HOST),
                PROFILE_SHARE,
                settings.text(MangaSettings.SMB_DOMAIN),
                settings.text(MangaSettings.SMB_USER),
                settings.text(MangaSettings.SMB_PASS));
    }

    private static MangaSmb.Auth mangaAuth(MangaSettings settings) {
        return new MangaSmb.Auth(
                settings.text(MangaSettings.SMB_HOST),
                settings.text(MangaSettings.SMB_SHARE),
                settings.text(MangaSettings.SMB_DOMAIN),
                settings.text(MangaSettings.SMB_USER),
                settings.text(MangaSettings.SMB_PASS));
    }

    private static String profilePath(MangaSettings settings, String file) {
        String folder = MangaSync.norm(settings.text(MangaSettings.SYNC_NEEV_FOLDER));
        if (folder.isEmpty()) {
            folder = "APP/NeeView/Profile";
        }
        return folder + "/" + file;
    }

    // ------------------------------------------------------------- helpers

    /** PC path from NeeView ("E:\a\b") -> canonical syncId ("E:/a/b"). */
    static String canonical(String pcPath) {
        String s = pcPath == null ? "" : pcPath.trim().replace('\\', '/');
        while (s.contains("//")) {
            s = s.replace("//", "/");
        }
        return s;
    }

    /** VR path ("smb://host/e/a/b") -> rel path inside the manga share. */
    private static String relativeToShare(MangaSettings settings, String vrPath) {
        String prefix = "smb://" + settings.text(MangaSettings.SMB_HOST) + "/"
                + settings.text(MangaSettings.SMB_SHARE) + "/";
        if (vrPath != null && vrPath.startsWith(prefix)) {
            return vrPath.substring(prefix.length());
        }
        return vrPath != null && vrPath.startsWith("smb://") ? vrPath : "";
    }

    /** A chapter key may carry a {@code |chapter} suffix; collapse it. */
    private static String chapterPath(String key) {
        if (key == null) {
            return "";
        }
        int bar = key.lastIndexOf('|');
        if (bar < 0) {
            return key;
        }
        String base = key.substring(0, bar);
        String chapter = key.substring(bar + 1);
        return base.endsWith("/" + chapter) ? base : base + "/" + chapter;
    }

    /** Sorted image file names of one chapter folder on the manga share. */
    private static List<String> pageNames(MangaSettings settings, String vrChapterPath) {
        List<String> names = new ArrayList<>();
        if (vrChapterPath != null && vrChapterPath.startsWith("115:")) {
            // 115 pages are named files in a netdisk folder, not on the SMB
            // share. Resolve them through the saved cookie so page indices
            // still match what NeeView sees on its X: mount.
            String cid = vrChapterPath.substring(4);
            String cookie = settings.text(MangaSettings.P115_COOKIE);
            if (!cid.isEmpty() && !cookie.isEmpty()) {
                try {
                    for (Manga115Api.DirEntry e : Manga115Api.listDir(cookie, cid)) {
                        if (!e.isDir && (MangaSettings.hasExtension(e.name,
                                settings.imageExtensions())
                                || MangaSettings.hasExtension(e.name,
                                settings.videoExtensions()))) {
                            names.add(e.name);
                        }
                    }
                    names.sort(NaturalOrder.INSTANCE);
                } catch (Exception e) {
                    Log.w(TAG, "115 pageNames " + cid + ": " + e);
                }
            }
            return names;
        }
        String rel = relativeToShare(settings, vrChapterPath);
        if (rel.isEmpty()) {
            return names;
        }
        try {
            for (MangaSmb.Entry e : MangaSmb.list(mangaAuth(settings), rel)) {
                if (!e.directory && MangaSettings.hasExtension(e.name,
                        settings.imageExtensions())) {
                    names.add(e.name);
                }
            }
            names.sort(NaturalOrder.INSTANCE);
        } catch (Exception e) {
            Log.w(TAG, "pageNames " + rel + ": " + e);
        }
        return names;
    }

    /** Page index and total of a named page in a chapter folder. */
    private static int[] pageOf(MangaSettings settings, String vrChapterPath,
                                String pageName) {
        List<String> names = pageNames(settings, vrChapterPath);
        int index = pageName == null ? -1 : names.indexOf(pageName);
        return new int[]{Math.max(0, index), names.size()};
    }

    /** Chapter key of the canonical book id, reusing an existing local key. */
    private static String chapterKey(Context context, MangaSettings settings,
                                     MangaReadingState state, String id) {
        for (MangaReadingState.Chapter c : state.allChapters()) {
            if (MangaSync.chapterSyncId(context, settings, c.key).equals(id)) {
                return c.key;
            }
        }
        // SMB chapter keys are the plain folder path (what the file browser
        // and reader use). Only extension sources need the "|chapter" suffix.
        String book = MangaSync.fromSyncId(context, settings, id);
        if (book.startsWith("smb://")) {
            return book;
        }
        int slash = book.lastIndexOf('/');
        if (slash <= 0) {
            return book;
        }
        return book + "|" + book.substring(slash + 1);
    }

    private static long parseStamp(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return System.currentTimeMillis();
        }
    }

    private static String formatStamp(long ms) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
                .format(STAMP);
    }

    private static boolean isDrivePath(String id) {
        return id.length() > 2 && Character.isLetter(id.charAt(0))
                && id.charAt(1) == ':' && id.charAt(2) == '/';
    }

    /** Repairs shelf keys saved by the old normaliser, which collapsed the
     *  double slash of smb:// into a single one. */
    private static String repairVrKey(String key) {
        if (key == null) {
            return "";
        }
        if (key.startsWith("smb:/") && !key.startsWith("smb://")) {
            return "smb://" + key.substring(5);
        }
        int i = key.indexOf("smb:/");
        if (i > 0 && !key.startsWith("smb://", i)) {
            return key.substring(0, i) + "smb://" + key.substring(i + 5);
        }
        return key;
    }

    /** VR shelf key of a PC path, used to deduplicate bookmarks. */
    private static String shelfKeyOf(Context context, MangaSettings settings, String pcPath) {
        String vr = MangaSync.fromSyncId(context, settings, canonical(pcPath));
        return MangaSync.norm(vr.isEmpty() ? canonical(pcPath) : vr);
    }

    // ------------------------------------------------------- full history

    /** Pulls the page number out of a NeeView Page field. Every image name
     *  carries its 1-based index ("...-72.jpg", "80.jpg", "(40).avif"). */
    private static int pageNumber(String name) {
        if (name == null || name.isEmpty()) {
            return -1;
    }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(name);
        int last = -1;
        while (m.find()) {
            try {
                last = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return last;
    }

    /** Parses "[72P", "[184P2V", "[100P3V" from a chapter/book folder name. */
    private static int totalPages(String name) {
        if (name == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*P",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(name);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    /**
     * Imports the ENTIRE NeeView history: every row whose path maps onto the
     * VR manga share becomes a read mark so folder/file frames are complete.
     * Page numbers come from the image file name and the total from the
     * folder's "[72P]" tag — no per-folder SMB listing needed, which is what
     * makes 12k rows finish in one pass. Only SMB/115 (drive-letter) rows are
     * imported; local-only entries are deliberately skipped.
     */
    private static int importHistoryMarks(Context context, MangaSettings settings,
                                          MangaReadingState state, JSONArray items,
                                          int skip) {
        java.util.List<MangaReadingState.Chapter> batch = new java.util.ArrayList<>();
        for (int i = skip; i < items.length(); i++) {
            JSONObject row = items.optJSONObject(i);
            if (row == null) {
                continue;
            }
            String pcPath = canonical(row.optString("Path", ""));
            String pageName = row.optString("Page", "");
            if (!isDrivePath(pcPath)) {
                continue;
            }
            String vrChapter = MangaSync.fromSyncId(context, settings, pcPath);
            if (!vrChapter.startsWith("smb://") && !vrChapter.startsWith("115:")
                    && !pcPath.toUpperCase(java.util.Locale.ROOT).startsWith("X:/")) {
                continue;   // unrelated drive, skip
            }
            // X: rows without a learned 115 key still get a stable mark so the
            // folder shows a frame; a later mapping upgrade can re-link them.
            if (vrChapter.startsWith("smb:/ ") || vrChapter.isEmpty()) {
                vrChapter = pcPath;
            }
            // A per-video row is "<chapter>\movie.mp4"; fold it into its parent.
            String folder = vrChapter;
            int slash = folder.lastIndexOf('/');
            String tail = slash > 0 ? folder.substring(slash + 1) : folder;
            if (!pageName.isEmpty() && pageName.equals(tail) && slash > 0) {
                folder = folder.substring(0, slash);
            }
            int page = pageNumber(pageName);       // 1-based
            int total = totalPages(tail);
            if (page < 0) {
                continue;
            }
            // Reader uses 0-based page indexes.
            int pageIndex = Math.max(0, page - 1);
            if (total < page) {
                total = page;                       // never below the reached page
            }
            MangaReadingState.Chapter c = new MangaReadingState.Chapter(folder);
            c.page = pageIndex;
            c.total = total;
            c.ts = parseStamp(row.optString("LastAccessTime", ""));
            batch.add(c);
        }
        int imported = state.importChapters(batch);
        Log.i(TAG, "imported " + imported + " history marks from " + items.length()
                + " rows (" + batch.size() + " drive rows)");
        return imported;
    }

    // ----------------------------------------------------------------- sync

    /** Reads PC History.json, applies its newest row to VR, then writes VR's
     *  newest position back into that same row. Bookmarks are merged too. */
    public static MangaSync.Result sync(Context context, MangaSettings settings) {
        try {
            String raw = MangaSmb.readText(syncAuth(settings),
                    profilePath(settings, HISTORY_FILE));
            if (raw == null || raw.trim().isEmpty()) {
                return MangaSync.Result.fail("SMB d 共享上没有找到 History.json");
            }
            JSONObject history = new JSONObject(raw);
            JSONArray items = history.optJSONArray("Items");
            if (items == null || items.length() == 0) {
                return MangaSync.Result.fail("NeeView 历史为空");
            }

            MangaReadingState state = MangaReadingState.get(context);

            // --- PC -> VR: whole history paints the blue/green frames ------
            // The 历史 page only shows one row, but NeeView's other thousands
            // of rows are real reading marks: import them so folders and files
            // keep their frames. List each chapter folder at most once.
            int imported = importHistoryMarks(context, settings, state, items, 1);

            // --- PC -> VR: newest row wins ---------------------------------
            JSONObject newest = items.optJSONObject(0);
            String pcPath = newest == null ? "" : newest.optString("Path", "");
            String pageName = newest == null ? "" : newest.optString("Page", "");
            long ts = parseStamp(newest == null ? "" : newest.optString("LastAccessTime", ""));
            String id = canonical(pcPath);
            String key = chapterKey(context, settings, state, id);
            int[] pageInfo = pageOf(settings, chapterPath(key), pageName);
            boolean changed = state.importChapter(key, pageInfo[0], pageInfo[1], ts);
            if (changed) {
                MangaSync.updateLegacyResume(context, settings, state,
                        java.util.Collections.singletonList(key));
            }
            MangaReadingState.Record record = new MangaReadingState.Record();
            String bookId = id.contains("/") ? id.substring(0, id.lastIndexOf('/')) : id;
            record.id = bookId;
            record.title = bookId.substring(bookId.lastIndexOf('/') + 1);
            record.source = bookId.contains("115:") ? "115" : "SMB";
            record.path = MangaSync.fromSyncId(context, settings, bookId);
            record.chapterKey = key;
            record.page = pageInfo[0];
            record.total = pageInfo[1];
            record.ts = ts;
            state.importRecord(record);

            // --- VR -> PC: update only the first row -----------------------
            // Which chapter the user actually watched in VR: the newest 历史
            // record written by noteHistory when the reader opened it. Using
            // the max-ts chapter is wrong because imported PC marks also carry
            // recent timestamps and would overwrite the real position.
            String pcKey = key;
            MangaReadingState.Chapter mark = state.chapter(key);
            // The newest VR-written history record is the book the user just
            // read (noteHistory runs when the reader opens a chapter).
            MangaReadingState.Record vrRecord = null;
            for (MangaReadingState.Record r : state.records()) {
                // VR-written records use a VR key (smb://, 115:, src:); the
                // record imported from NeeView above uses a PC "E:/" id.
                String rid = r.id == null ? "" : r.id;
                if (rid.startsWith("smb://") || rid.startsWith("115:")
                        || rid.startsWith("src:")) {
                    vrRecord = r;
                    break;  // records are newest-first
                }
            }
            if (vrRecord != null && vrRecord.chapterKey != null
                    && !vrRecord.chapterKey.isEmpty() && vrRecord.ts > ts) {
                MangaReadingState.Chapter candidate = state.chapter(vrRecord.chapterKey);
                if (candidate != null) {
                    mark = candidate;
                    key = vrRecord.chapterKey;
                }
            }
            String outId = canonical(MangaSync.syncId(context, settings, chapterPath(key)));
            if (!isDrivePath(outId)) {
                // Local / 115 / extension reading cannot be opened by NeeView.
                // Leave the PC row untouched instead of writing a bogus path.
                key = pcKey;
                mark = state.chapter(key);
                outId = id;
            }
            String outPage = pageName;
            if (isDrivePath(outId) && mark != null) {
                List<String> names = pageNames(settings, chapterPath(key));
                if (!names.isEmpty()) {
                    int idx = Math.max(0, Math.min(mark.page, names.size() - 1));
                    outPage = names.get(idx);
                }
            }

            JSONObject row = new JSONObject();
            row.put("Path", outId.replace('/', '\\'));
            row.put("LastAccessTime", formatStamp(
                    mark == null ? System.currentTimeMillis() : mark.ts));
            row.put("Page", outPage);
            row.put("Props", "IsWide");
            items.put(0, row);
            MangaSmb.writeText(syncAuth(settings), profilePath(settings, HISTORY_FILE),
                    history.toString(2));
            // History only feeds the recent list. NeeView reopens at
            // UserSetting.StartUp.LastBookV2, so that is what has to move.
            writeStartupPosition(settings, outId, outPage);

            int books = syncBookmarks(context, settings);
            settings.put(MangaSettings.SYNC_LAST_TS, System.currentTimeMillis());
            Log.i(TAG, "synced row: " + outId + " page=" + outPage + " bookmarks=" + books);
            return MangaSync.Result.ok("已同步 NeeView：最近阅读 + " + books + " 个书签");
        } catch (Exception e) {
            Log.w(TAG, "sync failed: " + e);
            return MangaSync.Result.fail("同步失败：" + e.getMessage());
        }
    }

    /**
     * Moves NeeView's "open last book" pointer to the position read in VR.
     * NeeView keeps it in UserSetting.json → Config.StartUp.LastBookV2
     * ({Path, Page, Props}) and reopens exactly that page on launch, so a
     * History.json rewrite alone never changes where the app starts.
     * NeeView rewrites this file when it exits, so VR sync must run while the
     * PC app is closed for the change to stick.
     */
    private static void writeStartupPosition(MangaSettings settings, String pcPath,
                                             String pageName) {
        if (pcPath == null || pcPath.isEmpty()) {
            return;
        }
        try {
            String path = profilePath(settings, USER_SETTING_FILE);
            String raw = MangaSmb.readText(syncAuth(settings), path);
            if (raw == null || raw.trim().isEmpty()) {
                Log.w(TAG, "UserSetting.json not found, startup position untouched");
                return;
            }
            JSONObject root = new JSONObject(stripBom(raw));
            JSONObject config = root.optJSONObject("Config");
            if (config == null) {
                config = new JSONObject();
                root.put("Config", config);
            }
            JSONObject startup = config.optJSONObject("StartUp");
            if (startup == null) {
                startup = new JSONObject();
                config.put("StartUp", startup);
            }
            String winPath = pcPath.replace('/', '\\');
            String props = "IsWide";
            JSONObject old = startup.optJSONObject("LastBookV2");
            if (old != null && old.optString("Props", "").length() > 0) {
                props = old.optString("Props");
            }
            JSONObject book = new JSONObject();
            book.put("Path", winPath);
            book.put("Page", pageName == null ? "" : pageName);
            book.put("Props", props);
            startup.put("LastBookV2", book);
            startup.put("LastBook", book);

            int cut = winPath.lastIndexOf('\\');
            String parent = cut > 2 ? winPath.substring(0, cut) : winPath;
            JSONObject folder = new JSONObject();
            folder.put("Path", parent);
            folder.put("Select", winPath);
            folder.put("FolderOrder", "FileName");
            folder.put("IsFolderRecursive", false);
            startup.put("LastFolder", folder);

            MangaSmb.writeText(syncAuth(settings), path, root.toString(2));
            Log.i(TAG, "startup position -> " + winPath + " | " + pageName);
        } catch (Exception e) {
            Log.w(TAG, "startup position write failed: " + e.getMessage());
        }
    }

    /** NeeView writes its JSON with a UTF-8 BOM; org.json chokes on it. */
    private static String stripBom(String raw) {
        return raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
    }

    /** Merges NeeView Bookmark.json with the VR shelf, both directions. */
    private static int syncBookmarks(Context context, MangaSettings settings) throws Exception {
        String raw = MangaSmb.readText(syncAuth(settings),
                profilePath(settings, BOOKMARK_FILE));
        if (raw == null || raw.trim().isEmpty()) {
            return 0;
        }
        JSONObject root = new JSONObject(raw);
        JSONObject nodes = root.optJSONObject("Nodes");
        if (nodes == null) {
            return 0;
        }
        JSONArray children = nodes.optJSONArray("Children");
        if (children == null) {
            children = new JSONArray();
        }

        // PC rows first, in the original order; dedupe by VR shelf key.
        Map<String, JSONObject> rows = new LinkedHashMap<>();
        for (int i = 0; i < children.length(); i++) {
            JSONObject o = children.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String pcPath = canonical(o.optString("Path", ""));
            if (pcPath.isEmpty()) {
                continue;
            }
            rows.put(shelfKeyOf(context, settings, pcPath), o);
        }

        List<String> currentShelf = new ArrayList<>();
        for (String vr : settings.bookshelf()) {
            currentShelf.add(repairVrKey(vr));
        }

        // VR shelf -> NeeView bookmarks (only entries with a PC drive path).
        for (String vr : currentShelf) {
            String pcPath = canonical(MangaSync.syncId(context, settings, vr));
            if (!isDrivePath(pcPath)) {
                continue; // 115 / extension source / local-only shelf entry
            }
            String key = shelfKeyOf(context, settings, pcPath);
            if (rows.containsKey(key)) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("Path", pcPath.replace('/', '\\'));
            o.put("Page", "");
            o.put("Props", "IsWide");
            rows.put(key, o);
        }

        // NeeView bookmarks -> VR shelf; keep VR-only entries as they are.
        List<String> shelf = new ArrayList<>();
        for (String vr : currentShelf) {
            if (!isDrivePath(canonical(MangaSync.syncId(context, settings, vr)))) {
                shelf.add(vr);
            }
        }
        for (String key : rows.keySet()) {
            shelf.add(key);
        }
        settings.setBookshelf(shelf);

        JSONArray out = new JSONArray();
        for (JSONObject o : rows.values()) {
            out.put(o);
        }
        nodes.put("Children", out);
        nodes.put("EntryTime", formatStamp(System.currentTimeMillis()));
        MangaSmb.writeText(syncAuth(settings), profilePath(settings, BOOKMARK_FILE),
                root.toString(2));
        Log.i(TAG, "bookmarks: " + rows.size() + " -> VR shelf " + shelf.size());
        return rows.size();
    }
}
