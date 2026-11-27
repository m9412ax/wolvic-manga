/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Direct NeeView profile sync: reads and writes NeeView's own History.json
 * over SMB, so the PC needs no bridge script at all.
 *
 * <p>NeeView stores one row per opened book/folder:
 * {@code {"Path":"E:\\合集\\书\\第01话","LastAccessTime":"...","Page":"072.jpg","Props":"IsWide"}}
 * with the newest row first. Only that first row is exchanged — the other
 * thousands of rows stay untouched on the PC. The VR side keeps ALL chapter
 * marks, which is what paints the blue / green frames on folders and files.</p>
 *
 * <p>The PC is authoritative: on every sync the newest PC row is applied to
 * VR (translated from E:/… to smb://host/e/… with the configured mapping),
 * then the first PC row is rewritten from VR's newest reading position.</p>
 */
public final class MangaNeeViewSync {

    private static final String TAG = "NeeViewSync";
    private static final String FILE = "History.json";
    /** NeeView expects 7 fractional digits and a numeric UTC offset. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSXXX");

    private MangaNeeViewSync() { }

    private static MangaSmb.Auth syncAuth(MangaSettings settings) {
        return new MangaSmb.Auth(
                settings.text(MangaSettings.SMB_HOST),
                settings.text(MangaSettings.SYNC_SMB_SHARE),
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

    private static String historyPath(MangaSettings settings) {
        String folder = MangaSync.norm(settings.text(MangaSettings.SYNC_NEEV_FOLDER));
        if (folder.isEmpty()) {
            folder = "APP/NeeView/Profile";
        }
        return folder + "/" + FILE;
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
        if (vrPath.startsWith(prefix)) {
            return vrPath.substring(prefix.length());
        }
        return vrPath.startsWith("smb://") ? vrPath : "";
    }

    /** Finds the page index and total of a named page in a chapter folder. */
    private static int[] pageOf(MangaSettings settings, String vrChapterPath,
                                String pageName) {
        String rel = relativeToShare(settings, vrChapterPath);
        if (rel.isEmpty()) {
            return new int[]{0, 0};
        }
        try {
            List<String> names = new ArrayList<>();
            for (MangaSmb.Entry e : MangaSmb.list(mangaAuth(settings), rel)) {
                if (!e.directory && MangaSettings.hasExtension(e.name,
                        settings.imageExtensions())) {
                    names.add(e.name);
                }
            }
            names.sort(NaturalOrder.INSTANCE);
            int index = pageName == null ? -1 : names.indexOf(pageName);
            return new int[]{Math.max(0, index), names.size()};
        } catch (Exception e) {
            Log.w(TAG, "pageOf " + rel + ": " + e);
            return new int[]{0, 0};
        }
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

    // ----------------------------------------------------------------- sync

    /** Reads PC History.json, applies its newest row to VR, then writes VR's
     *  newest position back into that same row. */
    public static MangaSync.Result sync(Context context, MangaSettings settings) {
        try {
            String raw = MangaSmb.readText(syncAuth(settings), historyPath(settings));
            if (raw == null || raw.trim().isEmpty()) {
                return MangaSync.Result.fail( "SMB 上没有找到 History.json");
            }
            JSONObject history = new JSONObject(raw);
            JSONArray items = history.optJSONArray("Items");
            if (items == null || items.length() == 0) {
                return MangaSync.Result.fail( "NeeView 历史为空");
            }

            // --- PC -> VR: newest row wins ---------------------------------
            JSONObject newest = items.optJSONObject(0);
            String pcPath = newest == null ? "" : newest.optString("Path", "");
            String pageName = newest == null ? "" : newest.optString("Page", "");
            long ts = parseStamp(newest == null ? "" : newest.optString("LastAccessTime", ""));
            String id = canonical(pcPath);
            MangaReadingState state = MangaReadingState.get(context);
            String key = chapterKey(context, settings, state, id);
            int[] pageInfo = pageOf(settings, key.contains("|")
                    ? key.substring(0, key.lastIndexOf('|')) : key, pageName);
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
            MangaReadingState.Record latest = state.records().isEmpty()
                    ? null : state.records().get(0);
            String outId = latest == null ? id : MangaSync.syncId(context, settings,
                    latest.path.isEmpty() ? latest.id : latest.path);
            MangaReadingState.Chapter mark = state.chapter(latest == null ? key : latest.chapterKey);
            int page = mark == null ? 0 : mark.page;
            int[] vrPage = pageOf(settings,
                    MangaSync.fromSyncId(context, settings,
                            outId.contains("/") ? outId : outId), null);
            String outPage = pageName;
            if (vrPage[1] > 0 && mark != null) {
                // Resolve the actual file name at the page index the reader
                // will resume from, so NeeView lands on the same image.
                String rel = relativeToShare(settings,
                        MangaSync.fromSyncId(context, settings, outId));
                try {
                    List<String> names = new ArrayList<>();
                    for (MangaSmb.Entry e : MangaSmb.list(mangaAuth(settings), rel)) {
                        if (!e.directory && MangaSettings.hasExtension(e.name,
                                settings.imageExtensions())) {
                            names.add(e.name);
                        }
                    }
                    names.sort(NaturalOrder.INSTANCE);
                    int idx = Math.max(0, Math.min(mark.page, names.size() - 1));
                    outPage = names.get(idx);
                } catch (Exception ignored) {
                    outPage = pageName;
                }
            }

            JSONObject row = new JSONObject();
            row.put("Path", outId.replace('/', '\\'));
            row.put("LastAccessTime", formatStamp(
                    mark == null ? System.currentTimeMillis() : mark.ts));
            row.put("Page", outPage);
            row.put("Props", "IsWide");
            items.put(0, row);
            settings.put(MangaSettings.SYNC_LAST_TS, System.currentTimeMillis());
            MangaSmb.writeText(syncAuth(settings), historyPath(settings), history.toString(2));
            Log.i(TAG, "synced newest row: " + outId + " page=" + outPage);
            return MangaSync.Result.ok( "已同步 NeeView 最近阅读：" + record.title);
        } catch (Exception e) {
            Log.w(TAG, "sync failed: " + e);
            return MangaSync.Result.fail( "同步失败：" + e.getMessage());
        }
    }
}
