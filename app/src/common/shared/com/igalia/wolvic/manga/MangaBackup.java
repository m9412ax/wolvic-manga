/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

/** Real JSON export/import of manga settings + reading progress. */
public final class MangaBackup {

    private static final String TAG = "MangaBackup";
    private static final String KEY_SETTINGS = "settings";
    private static final String KEY_PROGRESS = "progress";
    private static final String KEY_VERSION = "version";

    private MangaBackup() {}

    public static File defaultFile() {
        return new File(Environment.getExternalStorageDirectory(), "Manga/manga-backup.json");
    }

    public static File exportTo(Context context, File destination) throws Exception {
        MangaSettings settings = MangaSettings.get(context);
        JSONObject root = new JSONObject();
        root.put(KEY_VERSION, 1);
        root.put(KEY_SETTINGS, toJson(settings.prefs()));
        root.put(KEY_PROGRESS, toJson(settings.progressPrefs()));
        File parent = destination.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(destination)) {
            out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "exported " + destination.getAbsolutePath());
        return destination;
    }

    public static int importFrom(Context context, File source) throws Exception {
        byte[] bytes;
        try (FileInputStream in = new FileInputStream(source)) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            bytes = buffer.toByteArray();
        }
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        MangaSettings settings = MangaSettings.get(context);
        int count = 0;
        count += restore(settings.prefs(), root.optJSONObject(KEY_SETTINGS));
        count += restore(settings.progressPrefs(), root.optJSONObject(KEY_PROGRESS));
        Log.i(TAG, "imported " + count + " keys from " + source.getAbsolutePath());
        return count;
    }

    public static int clearProgress(Context context) {
        SharedPreferences prefs = MangaSettings.get(context).progressPrefs();
        int count = prefs.getAll().size();
        prefs.edit().clear().apply();
        return count;
    }

    private static JSONObject toJson(SharedPreferences prefs) throws Exception {
        JSONObject object = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            object.put(entry.getKey(), entry.getValue());
        }
        return object;
    }

    private static int restore(SharedPreferences prefs, JSONObject object) {
        if (object == null) {
            return 0;
        }
        SharedPreferences.Editor editor = prefs.edit();
        int count = 0;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.opt(key);
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            } else if (value instanceof Float || value instanceof Double) {
                editor.putFloat(key, ((Number) value).floatValue());
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
            } else {
                continue;
            }
            count++;
        }
        editor.apply();
        return count;
    }
}
