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
        String book = MangaSync.fromSyncId(context, settings, id);
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

    /** VR shelf key of a PC path, used to deduplicate bookmarks. */
    private static String shelfKeyOf(Context context, MangaSettings settings, String pcPath) {
        String vr = MangaSync.fromSyncId(context, settings, canonical(pcPath));
        return MangaSync.norm(vr.isEmpty() ? canonical(pcPath) : vr);
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

            // --- PC -> VR: newest row wins ---------------------------------
            JSONObject newest = items.optJSONObject(0);
            String pcPath = newest == null ? "" : newest.optString("Path", "");
            String pageName = newest == null ? "" : newest.optString("Page", "");
            long ts = parseStamp(newest == null ? "" : newest.optString("LastAccessTime", ""));
            String id = canonical(pcPath);
            MangaReadingState state = MangaReadingState.get(context);
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
            MangaReadingState.Chapter mark = state.chapter(key);
            MangaReadingState.Chapter newestMark = null;
            for (MangaReadingState.Chapter c : state.allChapters()) {
                if (newestMark == null || c.ts > newestMark.ts) {
                    newestMark = c;
                }
            }
            // The PC row we just imported is authoritative unless the reader
            // has moved to a newer page in VR after that timestamp.
            if (newestMark != null && newestMark.ts > ts) {
                mark = newestMark;
                key = newestMark.key;
            }
            String outId = canonical(MangaSync.syncId(context, settings, chapterPath(key)));
            if (outId.isEmpty()) {
                outId = id;
            }
            String outPage = pageName;
            if (mark != null) {
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

            int books = syncBookmarks(context, settings);
            settings.put(MangaSettings.SYNC_LAST_TS, System.currentTimeMillis());
            Log.i(TAG, "synced row: " + outId + " page=" + outPage + " bookmarks=" + books);
            return MangaSync.Result.ok("已同步 NeeView：最近阅读 + " + books + " 个书签");
        } catch (Exception e) {
            Log.w(TAG, "sync failed: " + e);
            return MangaSync.Result.fail("同步失败：" + e.getMessage());
        }
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

        // VR shelf -> NeeView bookmarks (only entries with a PC drive path).
        for (String vr : settings.bookshelf()) {
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
        for (String vr : settings.bookshelf()) {
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
