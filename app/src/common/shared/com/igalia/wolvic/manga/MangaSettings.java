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
    public static final String READER_PRELOAD = "reader_preload";
    public static final String READER_VIDEO_AUTOPLAY = "reader_video_autoplay";
    public static final String READER_VIDEO_NEXT_ON_END = "reader_video_next_on_end";
    public static final String READER_VIDEO_RESUME = "reader_video_resume";
    public static final String READER_TRANSITION = "reader_transition";
    public static final String READER_BACKGROUND = "reader_background";
    public static final String READER_DOUBLE_PAGE = "reader_double_page";

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
    public static final String CACHE_MB = "cache_mb";

    // Advanced
    public static final String ADV_HW_ACCEL = "adv_hw_accel";
    public static final String ADV_MAX_DECODE_PX = "adv_max_decode_px";
    public static final String ADV_LOGGING = "adv_logging";

    public static final String PROGRESS_PREFS = "manga_reader_progress";
    public static final String DOWNLOAD_QUEUE_PREFS = "manga_download_queue";

    private static final String DEFAULT_IMAGE_FORMATS = "jpg,jpeg,png,webp,gif,bmp";
    private static final String DEFAULT_VIDEO_FORMATS = "mp4,mkv,webm,avi,mov";

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
            case DOWNLOAD_ONLY_WIFI:
            case PROGRESS_ENABLED:
            case PROGRESS_VIDEO:
            case ADV_HW_ACCEL:
                return true;
            default:
                return false;
        }
    }

    public static int defaultInt(String key) {
        switch (key) {
            case READER_BRIGHTNESS:
                return 100;
            case READER_AUTO_ADVANCE_SEC:
                return 8;
            case READER_PRELOAD:
                return 1;
            case SOURCE_SPAN_COUNT:
                return 4;
            case SOURCE_THUMB_PX:
                return 480;
            case DOWNLOAD_PARALLEL:
                return 2;
            case DOWNLOAD_RETRY:
                return 2;
            case CACHE_MB:
                return 128;
            case ADV_MAX_DECODE_PX:
                return 1600;
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
                return 0x00000000; // transparent by default so the world shows through
        }
    }
}
