/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.content.Context;

import java.io.File;

/** Reports and clears the on-disk caches so users can see what each setting
 *  controls. Video buffering is RAM only (PrefetchVideoDataSource), so it has
 *  no disk footprint to report here. */
public final class MangaCacheManager {

    private MangaCacheManager() {}

    private static File base(Context c) {
        File d = c.getExternalCacheDir();
        if (d == null) d = c.getCacheDir();
        return d;
    }

    /** All image caches on disk: extension-source chapters + SMB/115 chapters
     *  + decoded AVIF page cache. */
    public static long imageCacheBytes(Context c) {
        File b = base(c);
        return dirSize(new File(b, "manga-sources"))
                + dirSize(new File(b, "remote-manga"))
                + dirSize(new File(b, "avif-cache"));
    }

    public static String imageCacheLabel(Context c) {
        long n = imageCacheBytes(c);
        if (n < 1024L) return n + " B";
        if (n < 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f KB", n / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.2f MB", n / (1024.0 * 1024.0));
    }

    /** Deletes every image cache directory. Safe: caches are regenerated on
     *  demand; reading progress is stored in prefs, not in these files. */
    public static long clearImageCache(Context c) {
        File b = base(c);
        long freed = dirSize(new File(b, "manga-sources"))
                + dirSize(new File(b, "remote-manga"))
                + dirSize(new File(b, "avif-cache"));
        deleteRecursively(new File(b, "manga-sources"));
        deleteRecursively(new File(b, "remote-manga"));
        deleteRecursively(new File(b, "avif-cache"));
        return freed;
    }

    private static long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        File[] fs = dir.listFiles();
        if (fs == null) return 0L;
        long n = 0L;
        for (File f : fs) n += f.isDirectory() ? dirSize(f) : f.length();
        return n;
    }

    private static void deleteRecursively(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) {
            if (f.isDirectory()) deleteRecursively(f);
            else f.delete();
        }
        dir.delete();
    }
}
