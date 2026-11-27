package com.igalia.wolvic.manga;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Disk cache for bookshelf / file-browser thumbnails.
 *
 * <p>Every cold start used to re-decode the first image of every folder on the
 * single decode thread, so a 40-folder shelf took seconds before covers
 * appeared. A scaled JPEG keyed by (path, mtime, size, target) turns that into
 * a plain file read that the OS page cache usually serves instantly.
 */
public final class MangaThumbCache {

    private static final String TAG = "MangaThumbCache";
    private static final String DIR = "manga-thumbs";
    private static final int MAX_FILES = 600;

    private MangaThumbCache() { }

    public static Bitmap load(Context context, File source, int targetPx) {
        File cache = cacheFile(context, source, targetPx);
        if (cache == null || !cache.isFile() || cache.length() == 0) {
            return null;
        }
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(cache.getAbsolutePath(), opts);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void save(Context context, File source, int targetPx, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        File cache = cacheFile(context, source, targetPx);
        if (cache == null) {
            return;
        }
        File parent = cache.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return;
        }
        File tmp = new File(cache.getAbsolutePath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out);
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(cache)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Keeps the cache directory bounded; called rarely from a worker thread. */
    public static void trim(Context context, int keep) {
        File dir = dir(context);
        if (dir == null) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length <= keep) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - keep; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    public static void trim(Context context) {
        trim(context, MAX_FILES);
    }

    private static File dir(Context context) {
        File base = context.getExternalCacheDir();
        if (base == null) {
            base = context.getCacheDir();
        }
        if (base == null) {
            return null;
        }
        File dir = new File(base, DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        return dir;
    }

    private static File cacheFile(Context context, File source, int targetPx) {
        if (source == null) {
            return null;
        }
        File dir = dir(context);
        if (dir == null) {
            return null;
        }
        String key = source.getAbsolutePath() + "|" + source.lastModified()
                + "|" + source.length() + "|" + targetPx;
        return new File(dir, md5(key) + ".jpg");
    }

    private static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "md5 failed: " + e);
            return Integer.toHexString(value.hashCode());
        }
    }
}
