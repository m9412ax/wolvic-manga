/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cross-device progress sync through a plain JSON file in an SMB share or a
 * 115 folder. The file is deliberately human-readable so a PC script (or a
 * curious user) can read and write it without this app.
 *
 * <pre>
 *   &lt;share root&gt;/manga-sync/mangahub-sync.json
 *   {
 *     "format": "mangahub-sync/1",
 *     "device": "VR-阅读器",
 *     "updatedAt": 1732700000000,
 *     "chapters": [ {"id":"E:/Manga/书/第01话","page":12,"total":30,"ts":17327...} ],
 *     "history":  [ {"id":"E:/Manga/书","title":"书","source":"SMB","ts":...} ],
 *     "books":    [ "E:/Manga/书" ]
 *   }
 * </pre>
 *
 * <p>Book identity is a path in PC notation. A mapping line such as
 * {@code smb://192.168.1.10/e/ => E:/} turns the VR-side SMB path into the
 * exact same id the PC uses, so both sides refer to the same book. Timestamps
 * (Unix ms) decide conflicts: the newer record wins, and page indices never
 * move backwards when both sides agree on the page count.</p>
 */
public final class MangaSync {

    private static final String TAG = "MangaSync";
    public static final String FORMAT = "mangahub-sync/1";
    public static final String FILE_NAME = "mangahub-sync.json";

    public static final int BACKEND_SMB = 0;
    public static final int BACKEND_115 = 1;

    private MangaSync() { }

    /** Result of one sync operation, safe to show in a Toast. */
    public static final class Result {
        public boolean ok;
        public String message = "";
        public int chapters;
        public int history;
        public int books;
        public long ts;

        static Result ok(String message) {
            Result r = new Result();
            r.ok = true;
            r.message = message;
            r.ts = System.currentTimeMillis();
            return r;
        }

        static Result fail(String message) {
            Result r = new Result();
            r.ok = false;
            r.message = message;
            return r;
        }
    }

    // ------------------------------------------------------- path mapping

    /** Normalises a path for use as a cross-device identity. */
    static String norm(String path) {
        if (path == null) {
            return "";
        }
        String s = path.trim().replace('\\', '/');
        while (s.contains("//")) {
            s = s.replace("//", "/");
        }
        if (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Applies the configured VR→PC prefix mapping to one path. */
    public static String syncId(Context context, MangaSettings settings, String path) {
        String raw = norm(path);
        if (raw.isEmpty()) {
            return "";
        }
        String config = settings.text(MangaSettings.SYNC_PATH_MAP);
        if (!config.isEmpty()) {
            for (String line : config.split("\\r?\\n")) {
                int arrow = line.indexOf("=>");
                if (arrow <= 0) {
                    continue;
                }
                String from = norm(line.substring(0, arrow));
                String to = norm(line.substring(arrow + 2));
                if (from.isEmpty() || !raw.startsWith(from)) {
                    continue;
                }
                String rest = raw.substring(from.length());
                if (rest.startsWith("/")) {
                    rest = rest.substring(1);
                }
                return to.isEmpty() ? rest : to + "/" + rest;
            }
        }
        // Fallback: for a remote key strip the protocol/host, keeping the path.
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.startsWith("smb://")) {
            int slash = raw.indexOf('/', "smb://".length());
            if (slash > 0) {
                int next = raw.indexOf('/', slash + 1);
                return next > 0 ? raw.substring(next + 1) : "";
            }
        }
        return raw;
    }

    /** Best-effort inverse of {@link #syncId} (used to re-link shelf entries). */
    public static String fromSyncId(Context context, MangaSettings settings, String id) {
        String raw = norm(id);
        if (raw.isEmpty()) {
            return "";
        }
        String config = settings.text(MangaSettings.SYNC_PATH_MAP);
        if (!config.isEmpty()) {
            for (String line : config.split("\\r?\\n")) {
                int arrow = line.indexOf("=>");
                if (arrow <= 0) {
                    continue;
                }
                String from = norm(line.substring(0, arrow));
                String to = norm(line.substring(arrow + 2));
                if (to.isEmpty() || !raw.startsWith(to)) {
                    continue;
                }
                String tail = raw.substring(to.length());
                if (tail.startsWith("/")) {
                    tail = tail.substring(1);
                }
                return from + (tail.isEmpty() ? "" : "/" + tail);
            }
        }
        return raw;
    }

    /** Maps a MangaReadingState chapter key (which may carry a {@code |chapter}
     *  suffix) to the canonical cross-device id. */
    public static String chapterSyncId(Context context, MangaSettings settings, String key) {
        String raw = norm(key);
        int bar = raw.lastIndexOf('|');
        if (bar < 0) {
            return syncId(context, settings, raw);
        }
        String book = syncId(context, settings, raw.substring(0, bar));
        String chapter = norm(raw.substring(bar + 1)).replace('/', '_');
        return book + "/" + chapter;
    }

    // ------------------------------------------------------------ export

    /** Builds the manifest from the local reading state + shelf. */
    public static JSONObject exportState(Context context, MangaSettings settings) {
        JSONObject root = new JSONObject();
        try {
            root.put("format", FORMAT);
            root.put("device", settings.text(MangaSettings.SYNC_DEVICE));
            root.put("updatedAt", System.currentTimeMillis());

            MangaReadingState state = MangaReadingState.get(context);
            JSONArray chapters = new JSONArray();
            for (MangaReadingState.Chapter c : state.allChapters()) {
                JSONObject item = new JSONObject();
                item.put("id", chapterSyncId(context, settings, c.key));
                item.put("page", c.page);
                item.put("total", c.total);
                item.put("ts", c.ts);
                chapters.put(item);
            }
            root.put("chapters", chapters);

            JSONArray history = new JSONArray();
            for (MangaReadingState.Record r : state.records()) {
                JSONObject item = new JSONObject();
                item.put("id", syncId(context, settings,
                        r.path.isEmpty() ? r.id : r.path));
                item.put("title", r.title);
                item.put("source", r.source);
                item.put("chapter", r.chapterKey == null ? ""
                        : chapterSyncId(context, settings, r.chapterKey));
                item.put("page", r.page);
                item.put("total", r.total);
                item.put("ts", r.ts);
                history.put(item);
            }
            root.put("history", history);

            JSONArray books = new JSONArray();
            for (String saved : settings.bookshelf()) {
                String id = syncId(context, settings, saved);
                if (!id.isEmpty()) {
                    books.put(id);
                }
            }
            root.put("books", books);
        } catch (JSONException e) {
            Log.w(TAG, "exportState: " + e);
        }
        return root;
    }

    // ------------------------------------------------------------ import

    /** Merges a remote manifest. Returns counters for the UI. */
    public static Result importState(Context context, MangaSettings settings, JSONObject remote) {
        if (remote == null) {
            return Result.fail("同步文件为空或格式错误");
        }
        String format = remote.optString("format", "");
        if (!format.startsWith("mangahub-sync/")) {
            return Result.fail("同步文件格式不兼容：" + format);
        }
        MangaReadingState state = MangaReadingState.get(context);
        Result result = Result.ok("已同步");

        // id -> local chapter key, built from marks we already own.
        Map<String, String> localChapters = new HashMap<>();
        for (MangaReadingState.Chapter c : state.allChapters()) {
            localChapters.put(chapterSyncId(context, settings, c.key), c.key);
        }
        JSONArray chapters = remote.optJSONArray("chapters");
        for (int i = 0; chapters != null && i < chapters.length(); i++) {
            JSONObject item = chapters.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String id = item.optString("id", "");
            if (id.isEmpty()) {
                continue;
            }
            String key = localChapters.get(id);
            if (key == null) {
                // Unknown book: store under the canonical id so a later local
                // folder with the same mapped path can pick the mark up.
                key = id;
            }
            boolean changed = state.importChapter(key,
                    item.optInt("page", 0), item.optInt("total", 0),
                    item.optLong("ts", 0));
            if (changed) {
                result.chapters++;
            }
        }

        Map<String, String> localRecords = new HashMap<>();
        for (MangaReadingState.Record r : state.records()) {
            localRecords.put(syncId(context, settings,
                    r.path.isEmpty() ? r.id : r.path), r.id);
        }
        JSONArray history = remote.optJSONArray("history");
        for (int i = 0; history != null && i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String id = item.optString("id", "");
            if (id.isEmpty()) {
                continue;
            }
            MangaReadingState.Record record = new MangaReadingState.Record();
            record.id = localRecords.containsKey(id) ? localRecords.get(id) : id;
            record.title = item.optString("title", id);
            record.source = item.optString("source", "");
            record.path = fromSyncId(context, settings, id);
            record.chapterKey = item.optString("chapter", "");
            record.page = item.optInt("page", 0);
            record.total = item.optInt("total", 0);
            record.ts = item.optLong("ts", 0);
            if (state.importRecord(record)) {
                result.history++;
            }
        }

        // Bookshelf: only re-link ids that map to a real local directory, so a
        // PC-only book can never leave a broken tile on the shelf.
        Set<String> existing = new HashSet<>(settings.bookshelf());
        int added = 0;
        JSONArray books = remote.optJSONArray("books");
        for (int i = 0; books != null && i < books.length(); i++) {
            String id = books.optString(i, "");
            if (id.isEmpty() || existing.contains(id)) {
                continue;
            }
            String localPath = fromSyncId(context, settings, id);
            if (localPath != null && !localPath.isEmpty()
                    && new File(localPath).isDirectory()) {
                settings.addToBookshelf(localPath);
                existing.add(localPath);
                added++;
            }
        }
        result.books = added;
        result.message = "章节 +" + result.chapters + " · 历史 +" + result.history
                + (added > 0 ? " · 书架 +" + added : "");
        return result;
    }

    // -------------------------------------------------------------- I/O

    private static MangaSmb.Auth smbAuth(MangaSettings settings) {
        return new MangaSmb.Auth(
                settings.text(MangaSettings.SMB_HOST),
                settings.text(MangaSettings.SMB_SHARE),
                settings.text(MangaSettings.SMB_DOMAIN),
                settings.text(MangaSettings.SMB_USER),
                settings.text(MangaSettings.SMB_PASS));
    }

    private static String relativePath(MangaSettings settings) {
        String folder = norm(settings.text(MangaSettings.SYNC_FOLDER));
        if (folder.isEmpty() || "/".equals(folder)) {
            folder = "manga-sync";
        }
        return folder + "/" + FILE_NAME;
    }

    /** Reads the remote manifest. Returns null when there is none yet. */
    public static JSONObject pull(Context context, MangaSettings settings) throws Exception {
        int backend = settings.integer(MangaSettings.SYNC_BACKEND);
        String raw;
        if (backend == BACKEND_115) {
            String cookie = settings.text(MangaSettings.P115_COOKIE);
            if (cookie.isEmpty()) {
                throw new IllegalStateException("请先登录 115");
            }
            String parent = settings.text(MangaSettings.SYNC_115_CID);
            String folderCid = Manga115Api.ensureFolder(cookie, parent,
                    settings.text(MangaSettings.SYNC_FOLDER));
            String fileId = Manga115Api.findFile(cookie, folderCid, FILE_NAME);
            if (fileId == null) {
                return null;
            }
            raw = new String(Manga115Api.fileBytes(cookie, fileId), StandardCharsets.UTF_8);
        } else {
            raw = MangaSmb.readText(smbAuth(settings), relativePath(settings));
        }
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        return new JSONObject(raw);
    }

    /** Writes the merged local state back to the sync folder. */
    public static void push(Context context, MangaSettings settings) throws Exception {
        String body = exportState(context, settings).toString(2);
        int backend = settings.integer(MangaSettings.SYNC_BACKEND);
        if (backend == BACKEND_115) {
            String cookie = settings.text(MangaSettings.P115_COOKIE);
            if (cookie.isEmpty()) {
                throw new IllegalStateException("请先登录 115");
            }
            String parent = settings.text(MangaSettings.SYNC_115_CID);
            String folderCid = Manga115Api.ensureFolder(cookie, parent,
                    settings.text(MangaSettings.SYNC_FOLDER));
            Manga115Api.uploadText(cookie, folderCid, FILE_NAME, body);
        } else {
            MangaSmb.writeText(smbAuth(settings), relativePath(settings), body);
        }
    }

    /** Pull → merge → push. Safe to call from a background thread only. */
    public static Result sync(Context context, MangaSettings settings) {
        try {
            JSONObject remote = pull(context, settings);
            Result merged = remote == null
                    ? Result.ok("远端还没有同步文件，已创建") : importState(context, settings, remote);
            push(context, settings);
            settings.put(MangaSettings.SYNC_LAST_TS, System.currentTimeMillis());
            merged.ok = true;
            merged.message = "同步完成 · " + merged.message;
            Log.i(TAG, merged.message);
            return merged;
        } catch (Exception e) {
            Log.w(TAG, "sync failed: " + e);
            return Result.fail("同步失败：" + e.getMessage());
        }
    }
}
