/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Single source of truth for every manga setting. Wolvic's stock settings UI
 * (SwitchSetting / RadioGroupSetting / SliderSetting / ...) writes here and the
 * reader, shelf and network sources all read from here, so no control is a
 * no-op stub.
 */
public final class MangaSettings {

    private static final String PREFS = "manga_settings";

    // Reading
    public static final String READER_DIRECTION = "reader_direction";
    public static final String READER_SCALE = "reader_scale";
    public static final String READER_TAP_ZONES = "reader_tap_zones";
    public static final String READER_VOLUME_KEYS = "reader_volume_keys";
    public static final String READER_KEEP_AWAKE = "reader_keep_awake";
    public static final String READER_BRIGHTNESS = "reader_brightness";
    public static final String READER_DOUBLE_TAP_ZOOM = "reader_double_tap_zoom";
    public static final String READER_AUTO_ADVANCE = "reader_auto_advance";
    public static final String READER_AUTO_ADVANCE_SEC = "reader_auto_advance_sec";
    public static final String READER_SHOW_PAGE_NUMBER = "reader_show_page_number";
    public static final String READER_CROP_BORDERS = "reader_crop_borders";
    /** In-RAM preload: how many NEIGHBOUR pages (each direction) are decoded
     *  into the Bitmap cache beforehand. Purely memory, independent of how
     *  many pages are fetched to disk. */
    public static final String READER_PRELOAD = "reader_preload";
    /** Disk preload: how many pages ahead of the reading position a remote
     *  chapter (SMB / 115 / figure source) downloads to local storage. Kept
     *  separate from READER_PRELOAD: a slow link wants deep disk prefetch, a
     *  fast link with huge images wants deep RAM preload. */
    public static final String READER_FETCH_AHEAD = "reader_fetch_ahead";
    /** Number of background threads that decode the preload window in parallel. */
    public static final String READER_PRELOAD_THREADS = "reader_preload_threads";
    public static final String READER_VIDEO_AUTOPLAY = "reader_video_autoplay";
    public static final String READER_VIDEO_NEXT_ON_END = "reader_video_next_on_end";
    public static final String READER_VIDEO_RESUME = "reader_video_resume";
    public static final String READER_TRANSITION = "reader_transition";
    public static final String READER_BACKGROUND = "reader_background";
    public static final String READER_DOUBLE_PAGE = "reader_double_page";
    /** Persistent window/canvas size multiplier. Unlike the temporary image
     *  zoom, this survives page changes and restarts. */
    public static final String READER_CANVAS_SCALE = "reader_canvas_scale";
    /** Continuous mode keeps its OWN canvas zoom. The continuous canvas is
     *  1:1 while single/double page is 16:9-ish, so sharing one multiplier made
     *  a comfortable zoom in one mode far too large/small in the other. */
    public static final String READER_CONTINUOUS_SCALE = "reader_continuous_scale";
    /** Video fit mode: 0 = fit screen, 1 = fit height, 2 = fit width. */
    public static final String READER_VIDEO_FIT = "reader_video_fit";
    /** Extra video zoom multiplier, adjustable in 0.5 steps. */
    public static final String READER_VIDEO_ZOOM = "reader_video_zoom";
    /** Continuous-mode canvas aspect ratio index (see ASPECT_LABELS). */
    public static final String READER_CONTINUOUS_ASPECT = "reader_continuous_aspect";

    /** width / height of the continuous-mode canvas. 1:1 is the default. */
    public static final String[] ASPECT_LABELS = {
            "1:1", "3:4", "4:3", "16:9", "9:16"};

    public static float aspectRatio(int index) {
        switch (index) {
            case 1: return 3.0f / 4.0f;
            case 2: return 4.0f / 3.0f;
            case 3: return 16.0f / 9.0f;
            case 4: return 9.0f / 16.0f;
            default: return 1.0f;
        }
    }
    /** Thumbstick vertical scroll multiplier in continuous (webtoon) mode.
     *  1.0 == the old hard-coded speed; 2.0 is the new default. */
    public static final String READER_SCROLL_SPEED = "reader_scroll_speed";

    // Sources
    public static final String SOURCE_ROOT = "source_root";
    public static final String SOURCE_NATURAL_SORT = "source_natural_sort";
    public static final String SOURCE_INCLUDE_HIDDEN = "source_include_hidden";
    public static final String SOURCE_VIDEO_FIRST = "source_video_first";
    public static final String SOURCE_RECURSIVE = "source_recursive";
    public static final String SOURCE_SPAN_COUNT = "source_span_count";
    public static final String SOURCE_IMAGE_FORMATS = "source_image_formats";
    public static final String SOURCE_VIDEO_FORMATS = "source_video_formats";
    public static final String SOURCE_THUMB_PX = "source_thumb_px";
    /** When true the extension catalogue also lists paid / members-only
     *  sections. Default false: those pages only expose a few preview images
     *  (the rest costs coins after login), so they are filtered out. */
    public static final String SHOW_PAID_SECTION = "source_show_paid";
    /** Extra path keywords treated as "paid section", comma separated. */
    public static final String SOURCE_PAID_KEYWORDS = "source_paid_keywords";
    /** Unified sort mode for local/SMB/115 listings (shared across sources):
     *  0 = name ascending (default), 1 = name descending,
     *  2 = date newest first, 3 = date oldest first. */
    public static final String SORT_MODE = "sort_mode";
    /** When true, media files in local/SMB/115 folder listings are ordered with
     *  videos pinned to the top regardless of the active name/date sort mode. */
    public static final String SORT_VIDEO_FIRST = "sort_video_first";
    /** When true (default) directories are pinned above files in every browser
     *  listing. Toggled from the quick-sheet and from the settings page. */
    public static final String SORT_FOLDER_FIRST = "sort_folder_first";
    /** Target tile width in dp for every cover grid (file browsers, shelf and
     *  extension sources). Smaller value = more columns = more items per row.
     *  Range 90..280, default 160. */
    public static final String THUMB_SIZE = "thumb_size";
    /** Bookshelf cover width in dp. Deliberately independent from
     *  {@link #THUMB_SIZE}: the shelf holds finished books (bigger covers look
     *  better) while the file browsers need compact tiles to show many files.
     *  Range 100..300, default 170. */
    public static final String SHELF_THUMB_SIZE = "shelf_thumb_size";
    /** File browser thumbnail aspect: 0 = 16:9 landscape, 1 = 3:4 portrait
     *  (default, what most manga pages actually look like), 2 = 9:16 tall. */
    public static final String THUMB_RATIO = "thumb_ratio";
    /** Bookshelf view: 0 = icon grid, 1 = compact list. */
    public static final String LIBRARY_VIEW_MODE = "library_view_mode";
    /** Bookshelf window size: 0 = small (legacy), 1 = medium (default), 2 = large. */
    public static final String LIBRARY_SIZE = "library_size";
    /** DNS-over-HTTPS provider for extension network requests (-1 = off). */
    public static final String NET_DOH = "net_doh";
    /** Thumbnail size multiplier inside a folder, persists between visits. */
    public static final String FOLDER_THUMB_SCALE = "folder_thumb_scale";
    /** Last browsed directory - restored on the next visit when no default
     *  position was explicitly chosen. */
    public static final String SOURCE_LAST_DIR = "source_last_dir";
    /** Most recently read manga folder, used by the global "continue" button. */
    public static final String LAST_READ_FOLDER = "last_read_folder";
    /** Bookshelf entries (JSON array of absolute folder paths). */
    public static final String BOOKSHELF_ITEMS = "bookshelf_items";

    // ------------------------------------------------------------- sync
    /** Master switch for the cross-device progress sync folder. Default off. */
    public static final String SYNC_ENABLED = "sync_enabled";
    /** Storage backend: 0 = SMB share, 1 = 115 netdisk. */
    public static final String SYNC_BACKEND = "sync_backend";
    /** Folder name created at the root of the chosen backend. */
    public static final String SYNC_FOLDER = "sync_folder";
    /** 115 cid of the folder that holds the sync directory ("" = drive root). */
    public static final String SYNC_115_CID = "sync_115_cid";
    /** Path mapping lines "vrPrefix => pcPrefix", one per line. Used to build
     *  the cross-device stable book id (e.g. smb://host/e/ => E:\\). */
    public static final String SYNC_PATH_MAP = "sync_path_map";
    /** Stable name of this device in the sync file. */
    public static final String SYNC_DEVICE = "sync_device";
    /** Push changes automatically when a chapter is closed. */
    public static final String SYNC_AUTO_PUSH = "sync_auto_push";
    /** Pull remote changes on entering the shelf, at most every N minutes. */
    public static final String SYNC_AUTO_PULL = "sync_auto_pull";
    /** Last successful sync timestamp (ms). */
    public static final String SYNC_LAST_TS = "sync_last_ts";
    /** Write VeeView's own History.json directly instead of our JSON + script. */
    public static final String SYNC_NEEV_DIRECT = "sync_neev_direct";
    /** SMB share that holds the PC profile (usually the D drive share). */
    public static final String SYNC_SMB_SHARE = "sync_smb_share";
    /** Folder under that share containing History.json. */
    public static final String SYNC_NEEV_FOLDER = "sync_neev_folder";
    /** 115 chapter key -> X:/… path on the PC, recorded when a chapter opens. */
    public static final String SYNC_115_PATHS = "sync_115_paths";

    public static final String WEBDAV_URL = "webdav_url";
    public static final String WEBDAV_USER = "webdav_user";
    public static final String WEBDAV_PASS = "webdav_pass";
    public static final String SMB_HOST = "smb_host";
    public static final String SMB_SHARE = "smb_share";
    public static final String SMB_DOMAIN = "smb_domain";
    public static final String SMB_USER = "smb_user";
    public static final String SMB_PASS = "smb_pass";
    public static final String SMB_PATH = "smb_path";
    public static final String SMB_MOUNT = "smb_mount";
    /** Default SMB remote path (relative to the share) used on auto-connect. */
    public static final String SMB_DEFAULT_PATH = "smb_default_path";

    // 115 netdisk (cookie-based login, obtained via in-app QR code)
    public static final String P115_COOKIE = "p115_cookie";
    public static final String P115_DEVICE = "p115_device";
    public static final String P115_LAST_CID = "p115_last_cid";
    public static final String P115_LAST_TITLE = "p115_last_title";
    /** 115 default folder (cid + display title) used when entering the netdisk. */
    public static final String P115_DEFAULT_CID = "p115_default_cid";
    public static final String P115_DEFAULT_TITLE = "p115_default_title";

    // Downloads
    public static final String DOWNLOAD_DIR = "download_dir";
    public static final String DOWNLOAD_ONLY_WIFI = "download_only_wifi";
    public static final String DOWNLOAD_PARALLEL = "download_parallel";
    public static final String DOWNLOAD_RETRY = "download_retry";
    public static final String DOWNLOAD_AUTO_NEXT = "download_auto_next";
    public static final String DOWNLOAD_DELETE_READ = "download_delete_read";

    // Progress / backup
    public static final String PROGRESS_ENABLED = "progress_enabled";
    public static final String PROGRESS_VIDEO = "progress_video";
    /** Single user-facing image DISK cap (MB). Governs extension-source
     *  chapters, SMB/115 on-demand chapters and the AVIF page cache. */
    public static final String IMAGE_CACHE_MB = "image_cache_mb";
    /** Local-file pre-buffer (MB): how much of a remote video's head is
     *  downloaded to disk before playback starts. Bigger = smoother start but
     *  longer wait. (Was the old in-RAM MediaDataSource slot size; key reused.) */
    public static final String VIDEO_BUFFER_MB = "video_buffer_mb";
    // Legacy keys kept only so old saved preferences still load; the UI no
    // longer exposes them and all code reads IMAGE_CACHE_MB instead.
    public static final String REMOTE_CACHE_MB = "remote_cache_mb";
    public static final String CACHE_MB = "cache_mb";
    public static final String SOURCE_CACHE_MB = "source_cache_mb";

    // Advanced
    public static final String ADV_HW_ACCEL = "adv_hw_accel";
    public static final String ADV_MAX_DECODE_PX = "adv_max_decode_px";
    public static final String ADV_LOGGING = "adv_logging";

    public static final String PROGRESS_PREFS = "manga_reader_progress";
    public static final String DOWNLOAD_QUEUE_PREFS = "manga_download_queue";

    private static final String DEFAULT_IMAGE_FORMATS = "jpg,jpeg,png,webp,gif,bmp,avif,avifs";
    private static final String DEFAULT_VIDEO_FORMATS = "mp4,mkv,webm,avi,mov,3gp,ts,m4v,hevc,h265,av1";

    private final Context mContext;

    private MangaSettings(Context context) {
        mContext = context.getApplicationContext();
    }

    public static MangaSettings get(@NonNull Context context) {
        return new MangaSettings(context);
    }

    public SharedPreferences prefs() {
        return mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public SharedPreferences progressPrefs() {
        return mContext.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE);
    }

    /** Persisted download queue state (last download result per chapter folder). */
    public SharedPreferences downloadQueuePrefs() {
        return mContext.getSharedPreferences(DOWNLOAD_QUEUE_PREFS, Context.MODE_PRIVATE);
    }

    public void register(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs().registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregister(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs().unregisterOnSharedPreferenceChangeListener(listener);
    }

    public boolean getBoolean(String key, boolean fallback) {
        return prefs().getBoolean(key, fallback);
    }

    public int getInt(String key, int fallback) {
        return prefs().getInt(key, fallback);
    }

    public String getString(String key, String fallback) {
        return prefs().getString(key, fallback);
    }

    public void put(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
    }

    public void put(String key, int value) {
        prefs().edit().putInt(key, value).apply();
    }

    public void put(String key, String value) {
        prefs().edit().putString(key, value == null ? "" : value).apply();
    }

    public float getFloat(String key, float fallback) {
        return prefs().getFloat(key, fallback);
    }

    public void put(String key, float value) {
        prefs().edit().putFloat(key, value).apply();
    }

    public void put(String key, long value) {
        prefs().edit().putLong(key, value).apply();
    }

    // ------------------------------------------------------------ defaults

    public static boolean defaultBoolean(String key) {
        switch (key) {
            case READER_TAP_ZONES:
            case READER_VOLUME_KEYS:
            case READER_KEEP_AWAKE:
            case READER_DOUBLE_TAP_ZOOM:
            case READER_SHOW_PAGE_NUMBER:
            case READER_VIDEO_AUTOPLAY:
            case READER_VIDEO_NEXT_ON_END:
            case READER_VIDEO_RESUME:
            case SOURCE_NATURAL_SORT:
            case SOURCE_VIDEO_FIRST:
            case SORT_VIDEO_FIRST:
            case SORT_FOLDER_FIRST:
            case DOWNLOAD_ONLY_WIFI:
            case PROGRESS_ENABLED:
            case PROGRESS_VIDEO:
            case ADV_HW_ACCEL:
            case SYNC_AUTO_PUSH:
            case SYNC_NEEV_DIRECT:
                return true;
            default:
                return false;
        }
    }

    /** Window / cover scale for the 小(0) / 中(1) / 大(2) bookshelf views. */
    public static float librarySizeFactor(int size) {
        return size == 2 ? 1.8f : size == 1 ? 1.4f : 1.0f;
    }

    public static int defaultInt(String key) {
        switch (key) {
            case THUMB_RATIO:
                return 1;
            case THUMB_SIZE:
                return 160;
            case SHELF_THUMB_SIZE:
                return 170;
            case READER_BRIGHTNESS:
                return 100;
            case READER_AUTO_ADVANCE_SEC:
                return 8;
            case READER_PRELOAD:
                return 4;
            case READER_FETCH_AHEAD:
                return 4;
            case READER_PRELOAD_THREADS:
                return 3;
            case SOURCE_SPAN_COUNT:
                return 4;
            case SOURCE_THUMB_PX:
                return 480;
            case DOWNLOAD_PARALLEL:
                return 2;
            case DOWNLOAD_RETRY:
                return 2;
            case IMAGE_CACHE_MB:
                return 1024;
            case CACHE_MB:
                return 256;
            case REMOTE_CACHE_MB:
                return 512;
            case VIDEO_BUFFER_MB:
                return 12;
            case SOURCE_CACHE_MB:
                return 1024;
            case LIBRARY_SIZE:
                return 1;
            case NET_DOH:
                return -1;
            case ADV_MAX_DECODE_PX:
                return 1600;
            case READER_VIDEO_FIT:
                return 3; // original size, never upscaled / stretched
            case READER_DIRECTION:
            case READER_SCALE:
            case READER_TRANSITION:
            default:
                return 0;
            case READER_BACKGROUND:
                return 3; // transparent
        }
    }

    public static String defaultString(String key) {
        switch (key) {
            case SYNC_FOLDER:
                return "manga-sync";
            case SYNC_DEVICE:
                return "VR-阅读器";
            case SYNC_SMB_SHARE:
                return "d";
            case SYNC_NEEV_FOLDER:
                return "APP/NeeView/Profile";
            case SOURCE_ROOT:
                return new File(Environment.getExternalStorageDirectory(), "Manga").getAbsolutePath();
            case SOURCE_IMAGE_FORMATS:
                return DEFAULT_IMAGE_FORMATS;
            case SOURCE_VIDEO_FORMATS:
                return DEFAULT_VIDEO_FORMATS;
            case DOWNLOAD_DIR:
                return new File(Environment.getExternalStorageDirectory(), "Manga/Downloads").getAbsolutePath();
            case SMB_PATH:
                return "/";
            case SMB_MOUNT:
                return new File(Environment.getExternalStorageDirectory(), "Manga/SMB").getAbsolutePath();
            case P115_DEVICE:
                // Use a TV device id so QR login does not kick the user's
                // phone/web session offline.
                return "tv";
            default:
                return "";
        }
    }

    public boolean bool(String key) {
        return getBoolean(key, defaultBoolean(key));
    }

    public int integer(String key) {
        return getInt(key, defaultInt(key));
    }

    public String text(String key) {
        return getString(key, defaultString(key));
    }

    public void resetAll() {
        prefs().edit().clear().apply();
    }

    // ----------------------------------------------------------- accessors

    public String[] imageExtensions() {
        return parseFormats(text(SOURCE_IMAGE_FORMATS), DEFAULT_IMAGE_FORMATS);
    }

    public String[] videoExtensions() {
        return parseFormats(text(SOURCE_VIDEO_FORMATS), DEFAULT_VIDEO_FORMATS);
    }

    private static String[] parseFormats(String csv, String fallbackCsv) {
        String source = (csv == null || csv.trim().isEmpty()) ? fallbackCsv : csv;
        List<String> result = new ArrayList<>();
        for (String part : source.split("[,;\\s]+")) {
            String ext = part.trim().toLowerCase(Locale.ROOT).replace(".", "");
            if (!ext.isEmpty() && !result.contains(ext)) {
                result.add(ext);
            }
        }
        if (result.isEmpty()) {
            for (String part : fallbackCsv.split(",")) {
                result.add(part.trim());
            }
        }
        return result.toArray(new String[0]);
    }

    public static boolean hasExtension(String name, String[] extensions) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (lower.endsWith("." + ext)) {
                return true;
            }
        }
        return false;
    }

    public int backgroundColor() {
        switch (integer(READER_BACKGROUND)) {
            case 1:
                return 0xFFFFFFFF;
            case 2:
                return 0xFF808080;
            case 3:
                return 0x00000000; // fully transparent
            default:
                // Opaque black: a translucent window composites with the VR
                // environment every frame, which showed up as flicker over
                // background images. "黑色" (0) must actually be black.
                return 0xFF000000;
        }
    }

    // ------------------------------------------- bookshelf / navigation

    /** Per-source default directory ("LOCAL", "SMB", "WEBDAV"). */
    public String defaultDir(String source) {
        return prefs().getString("default_dir:" + source, "");
    }

    public void setDefaultDir(String source, String path) {
        prefs().edit().putString("default_dir:" + source, path == null ? "" : path).apply();
    }

    public String lastDir() {
        return prefs().getString(SOURCE_LAST_DIR, "");
    }

    public void setLastDir(String path) {
        prefs().edit().putString(SOURCE_LAST_DIR, path == null ? "" : path).apply();
    }

    public String lastReadFolder() {
        return prefs().getString(LAST_READ_FOLDER, "");
    }

    public void setLastReadFolder(String path) {
        prefs().edit().putString(LAST_READ_FOLDER, path == null ? "" : path).apply();
    }

    public List<String> bookshelf() {
        List<String> out = new ArrayList<>();
        String raw = prefs().getString(BOOKSHELF_ITEMS, null);
        if (raw == null) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                String saved = arr.getString(i);
                String fixed = saved.startsWith("remote:smb:") || saved.startsWith("smb:/")
                        ? MangaSync.repairSmbKey(saved, this) : saved;
                if (!fixed.equals(saved)) {
                    changed = true;
                }
                out.add(fixed);
            }
            if (changed) {
                writeBookshelf(out);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** Replaces the whole shelf (used when importing NeeView bookmarks). */
    public void setBookshelf(List<String> list) {
        writeBookshelf(list == null ? new ArrayList<>() : list);
    }

    // --------------------------------------------- 115 -> PC X: drive mapping

    /** Remembers the PC path of a 115 chapter when it is first opened. */
    public void put115Path(String chapterKey, String pcPath) {
        if (chapterKey == null || chapterKey.isEmpty() || pcPath == null || pcPath.isEmpty()) {
            return;
        }
        JSONObject map = paths115();
        try {
            map.put(chapterKey, pcPath);
            prefs().edit().putString(SYNC_115_PATHS, map.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /** PC path of a 115 chapter key, or "". */
    public String path115(String chapterKey) {
        if (chapterKey == null || chapterKey.isEmpty()) {
            return "";
        }
        return paths115().optString(chapterKey, "");
    }

    /** Reverse lookup: 115 chapter key of a PC path, or "". */
    public String key115ForPath(String pcPath) {
        if (pcPath == null || pcPath.isEmpty()) {
            return "";
        }
        JSONObject map = paths115();
        for (java.util.Iterator<String> it = map.keys(); it.hasNext();) {
            String key = it.next();
            if (pcPath.equals(map.optString(key, ""))) {
                return key;
            }
        }
        return "";
    }

    private JSONObject paths115() {
        String raw = prefs().getString(SYNC_115_PATHS, null);
        if (raw == null) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public boolean isInBookshelf(String path) {
        return path != null && bookshelf().contains(path);
    }

    public void addToBookshelf(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        List<String> list = bookshelf();
        if (!list.contains(path)) {
            list.add(path);
            writeBookshelf(list);
        }
    }

    public void removeFromBookshelf(String path) {
        List<String> list = bookshelf();
        if (list.remove(path)) {
            writeBookshelf(list);
        }
    }

    private void writeBookshelf(List<String> list) {
        JSONArray arr = new JSONArray();
        for (String path : list) {
            arr.put(path);
        }
        prefs().edit().putString(BOOKSHELF_ITEMS, arr.toString()).apply();
    }

    // --------------------------------------------------- custom key bindings

    /** Scope: GLOBAL bindings fire in both the library and the reader.
     *  READER bindings only fire while a page is open. */
    public static final int SCOPE_GLOBAL = 0;
    public static final int SCOPE_READER = 1;

    public static final String ACTION_PREV_PAGE = "prev_page";
    public static final String ACTION_NEXT_PAGE = "next_page";
    public static final String ACTION_PREV_FOLDER = "prev_folder";
    public static final String ACTION_NEXT_FOLDER = "next_folder";
    /** Temporary image-content zoom (Matrix). The window/canvas keeps its size
     *  and the zoom resets to 100% on the next page. */
    public static final String ACTION_ZOOM_IN = "zoom_in";
    public static final String ACTION_ZOOM_OUT = "zoom_out";
    /** Persistent canvas/window zoom. Changes worldWidth/width/height, is saved
     *  to prefs and therefore survives page changes and app restarts. */
    public static final String ACTION_CANVAS_ZOOM_IN = "canvas_zoom_in";
    public static final String ACTION_CANVAS_ZOOM_OUT = "canvas_zoom_out";
    public static final String ACTION_RESET_POS = "reset_position";
    public static final String ACTION_TOGGLE_DOUBLE = "toggle_double";
    /** Hold modifier: while this key is down the joystick resizes/repositions the window. */
    public static final String ACTION_RESIZE_MOD = "resize_modifier";

    /** Virtual keycodes for the four thumbstick directions. Stick axes arrive
     *  through VRBrowser::HandleScrollEvent rather than as Android KeyEvents,
     *  so they need stable identifiers of their own to be capturable and
     *  bindable. Values are far above any real Android keycode. */
    public static final int KEY_STICK_LEFT = 1001;
    public static final int KEY_STICK_RIGHT = 1002;
    public static final int KEY_STICK_UP = 1003;
    public static final int KEY_STICK_DOWN = 1004;

    public static boolean isStickKey(int code) {
        return code >= KEY_STICK_LEFT && code <= KEY_STICK_DOWN;
    }

    /** Human-readable name for every key we can capture. Replaces the raw
     *  KEYCODE_BUTTON_A(97) style text in the shortcut editor. */
    public static String keyLabel(int code) {
        switch (code) {
            case KEY_STICK_LEFT: return "摇杆 ← 左";
            case KEY_STICK_RIGHT: return "摇杆 → 右";
            case KEY_STICK_UP: return "摇杆 ↑ 上";
            case KEY_STICK_DOWN: return "摇杆 ↓ 下";
            case android.view.KeyEvent.KEYCODE_BUTTON_A: return "A 键";
            case android.view.KeyEvent.KEYCODE_BUTTON_B: return "B 键";
            case android.view.KeyEvent.KEYCODE_BUTTON_X: return "X 键";
            case android.view.KeyEvent.KEYCODE_BUTTON_Y: return "Y 键";
            case android.view.KeyEvent.KEYCODE_BUTTON_R1: return "侧握键(侧边)";
            case android.view.KeyEvent.KEYCODE_BUTTON_L1: return "左侧键";
            case android.view.KeyEvent.KEYCODE_BUTTON_R2: return "扳机键(确认)";
            case android.view.KeyEvent.KEYCODE_BUTTON_L2: return "左扳机键";
            case android.view.KeyEvent.KEYCODE_BUTTON_THUMBL: return "左摇杆按下";
            case android.view.KeyEvent.KEYCODE_BUTTON_THUMBR: return "右摇杆按下";
            case android.view.KeyEvent.KEYCODE_BUTTON_MODE: return "菜单键";
            case android.view.KeyEvent.KEYCODE_BUTTON_1: return "附加键 1";
            case android.view.KeyEvent.KEYCODE_BUTTON_2: return "附加键 2";
            case android.view.KeyEvent.KEYCODE_BUTTON_START: return "开始键";
            case android.view.KeyEvent.KEYCODE_BUTTON_SELECT: return "选择键";
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: return "方向键 ←";
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: return "方向键 →";
            case android.view.KeyEvent.KEYCODE_DPAD_UP: return "方向键 ↑";
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN: return "方向键 ↓";
            case android.view.KeyEvent.KEYCODE_VOLUME_UP: return "音量 +";
            case android.view.KeyEvent.KEYCODE_VOLUME_DOWN: return "音量 −";
            case android.view.KeyEvent.KEYCODE_BACK: return "返回键";
            case android.view.KeyEvent.KEYCODE_ENTER: return "确认键";
            default: {
                String raw = android.view.KeyEvent.keyCodeToString(code);
                return raw.startsWith("KEYCODE_") ? raw.substring(8).replace('_', ' ') : raw;
            }
        }
    }

    /** True when at least one binding uses the given virtual stick direction. */
    public boolean hasStickBinding(int stickKey) {
        for (KeyBinding b : getBindings()) {
            for (int k : b.keys) {
                if (k == stickKey) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final String KEY_BINDINGS = "manga_key_bindings";
    private static final String KEY_CONTROLLER_BINDINGS = "manga_controller_bindings_v1";
    private static final String[] ALL_ACTIONS = {
            ACTION_PREV_PAGE, ACTION_NEXT_PAGE, ACTION_PREV_FOLDER, ACTION_NEXT_FOLDER,
            ACTION_ZOOM_IN, ACTION_ZOOM_OUT, ACTION_CANVAS_ZOOM_IN, ACTION_CANVAS_ZOOM_OUT,
            ACTION_RESET_POS, ACTION_TOGGLE_DOUBLE, ACTION_RESIZE_MOD
    };

    public static final class KeyBinding {
        public String action;
        /** First key of the chord. Kept for code that still uses a single key. */
        public int keyCode;
        /** Full chord. A single-key binding is just a one-element array. */
        public int[] keys;
        public int scope;

        public KeyBinding(String action, int keyCode, int scope) {
            this(action, new int[]{keyCode}, scope);
        }

        public KeyBinding(String action, int[] keys, int scope) {
            this.action = action;
            this.keys = (keys == null || keys.length == 0) ? new int[]{0} : keys.clone();
            this.keyCode = this.keys[0];
            this.scope = scope;
        }

        public String keySignature() {
            StringBuilder sb = new StringBuilder();
            for (int k : keys) {
                sb.append(k).append(',');
            }
            return sb.toString();
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("action", action);
            o.put("key", keyCode);
            JSONArray chord = new JSONArray();
            for (int k : keys) {
                chord.put(k);
            }
            o.put("keys", chord);
            o.put("scope", scope);
            return o;
        }

        public static KeyBinding fromJson(JSONObject o) throws JSONException {
            JSONArray chord = o.optJSONArray("keys");
            int[] keys;
            if (chord != null && chord.length() > 0) {
                keys = new int[chord.length()];
                for (int i = 0; i < chord.length(); i++) {
                    keys[i] = chord.getInt(i);
                }
            } else {
                keys = new int[]{o.getInt("key")};
            }
            return new KeyBinding(o.getString("action"), keys,
                    o.optInt("scope", SCOPE_GLOBAL));
        }
    }

    public List<KeyBinding> getBindings() {
        List<KeyBinding> out = new ArrayList<>();
        String raw = prefs().getString(KEY_BINDINGS, null);
        if (raw == null) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(KeyBinding.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            // Corrupt payload: ignore and fall back to empty.
        }
        return out;
    }

    public void setBindings(List<KeyBinding> bindings) {
        JSONArray arr = new JSONArray();
        for (KeyBinding b : bindings) {
            try {
                arr.put(b.toJson());
            } catch (JSONException ignored) {
                // skip unserializable entry
            }
        }
        prefs().edit().putString(KEY_BINDINGS, arr.toString()).apply();
    }

    public void addBinding(KeyBinding b) {
        List<KeyBinding> list = getBindings();
        // Replace any existing binding for the same chord to avoid duplicates.
        list.removeIf(existing -> existing.keySignature().equals(b.keySignature()));
        list.add(b);
        setBindings(list);
    }

    public void removeBinding(int index) {
        List<KeyBinding> list = getBindings();
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            setBindings(list);
        }
    }

    /** Seeds a sensible default set, plus controller-native keys once. */
    public void ensureDefaultBindings() {
        String raw = prefs().getString(KEY_BINDINGS, null);
        if (raw == null || raw.isEmpty()) {
            List<KeyBinding> defaults = new ArrayList<>();
            defaults.add(new KeyBinding(ACTION_PREV_PAGE, android.view.KeyEvent.KEYCODE_DPAD_LEFT, SCOPE_READER));
            defaults.add(new KeyBinding(ACTION_NEXT_PAGE, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, SCOPE_READER));
            defaults.add(new KeyBinding(ACTION_ZOOM_OUT, android.view.KeyEvent.KEYCODE_VOLUME_DOWN, SCOPE_READER));
            defaults.add(new KeyBinding(ACTION_ZOOM_IN, android.view.KeyEvent.KEYCODE_VOLUME_UP, SCOPE_READER));
            defaults.add(new KeyBinding(ACTION_RESIZE_MOD, android.view.KeyEvent.KEYCODE_BUTTON_R1, SCOPE_READER));
            setBindings(defaults);
        }
        // Native OpenXR buttons are now forwarded as these Android keycodes
        // (see VRBrowserActivity.controllerButtonToKeyCode). Seed them once so
        // the controller works out of the box; afterwards the user may edit or
        // delete them freely.
        if (!prefs().getBoolean(KEY_CONTROLLER_BINDINGS, false)) {
            addBinding(new KeyBinding(ACTION_PREV_PAGE, android.view.KeyEvent.KEYCODE_BUTTON_X, SCOPE_READER));
            addBinding(new KeyBinding(ACTION_NEXT_PAGE, android.view.KeyEvent.KEYCODE_BUTTON_A, SCOPE_READER));
            addBinding(new KeyBinding(ACTION_ZOOM_OUT, android.view.KeyEvent.KEYCODE_BUTTON_Y, SCOPE_READER));
            addBinding(new KeyBinding(ACTION_ZOOM_IN, android.view.KeyEvent.KEYCODE_BUTTON_THUMBL, SCOPE_READER));
            prefs().edit().putBoolean(KEY_CONTROLLER_BINDINGS, true).apply();
        }
    }

    public static String actionLabel(String action) {
        switch (action) {
            case ACTION_PREV_PAGE: return "上一页";
            case ACTION_NEXT_PAGE: return "下一页";
            case ACTION_PREV_FOLDER: return "上一个文件夹";
            case ACTION_NEXT_FOLDER: return "下一个文件夹";
            case ACTION_ZOOM_IN: return "图片放大(临时)";
            case ACTION_ZOOM_OUT: return "图片缩小(临时)";
            case ACTION_CANVAS_ZOOM_IN: return "画布放大(持久)";
            case ACTION_CANVAS_ZOOM_OUT: return "画布缩小(持久)";
            case ACTION_RESET_POS: return "重置位置/大小";
            case ACTION_TOGGLE_DOUBLE: return "双页切换";
            case ACTION_RESIZE_MOD: return "缩放/移动修饰键(按住)";
            default: return action;
        }
    }

    public static String scopeLabel(int scope) {
        return scope == SCOPE_READER ? "仅看图" : "全局";
    }

    public static String[] allActions() {
        return ALL_ACTIONS.clone();
    }
}
