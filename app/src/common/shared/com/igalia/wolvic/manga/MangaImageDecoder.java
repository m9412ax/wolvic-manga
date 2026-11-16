/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;

import java.io.File;

public final class MangaImageDecoder {

    private static final android.util.LruCache<String, int[]> DIMENSIONS =
            new android.util.LruCache<>(256);
    private static final Object AVIF_LOCK = new Object();
    private static final java.util.concurrent.atomic.AtomicInteger sForegroundAvifWaiters =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final ThreadLocal<Boolean> AVIF_PRIORITY =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Decoded AVIF pages transcoded to JPEG on disk. A native AV1 decode takes
     *  1-3 s on this device; the JPEG re-read takes ~100 ms, so revisits and
     *  app restarts stay fast even after the memory cache evicts the page. */
    private static final long DISK_CACHE_MAX = 512L * 1024 * 1024;
    private static volatile File sDiskCacheDir;
    private static final java.util.concurrent.ExecutorService DISK_WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "manga-avif-cache");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    /** Marks the calling thread's next AVIF decode as foreground (page the
     *  user is waiting for). Preload/cover decodes yield while one is active,
     *  so a page turn never queues behind a chain of 8K preloads. */
    public static void setAvifPriority(boolean priority) {
        AVIF_PRIORITY.set(priority);
    }

    private static class NativeLibraries {
        static {
            System.loadLibrary("imagedecoder2");
            System.loadLibrary("manga-decoder");
        }

        static void load() { }
    }

    private static native int[] nativeReadBounds(String path);
    private static native Bitmap nativeDecodeSdr(String path, int sample, long byteBudget,
                                                 int[] sourceSize);

    private MangaImageDecoder() { }

    private static String fileKey(File file) {
        return file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified();
    }

    @Nullable
    public static int[] cachedDimensions(File file) {
        return DIMENSIONS.get(fileKey(file));
    }

    /** Kotlin names its factory ImageDecoder.new(InputStream); "new" is a
     *  Java keyword, so invoke it reflectively. */
    private static Object createNativeDecoder(java.io.InputStream in) throws Exception {
        Class<?> cls = Class.forName("ca.mpreg.imagedecoder.ImageDecoder");
        java.lang.reflect.Method m = cls.getMethod("new", java.io.InputStream.class);
        return m.invoke(null, in);
    }

    private static void closeNativeDecoder(Object decoder) {
        if (decoder == null) {
            return;
        }
        try {
            decoder.getClass().getMethod("close").invoke(decoder);
        } catch (Throwable ignored) { }
    }

    private static Object decodeNative(Object decoder) throws Exception {
        return decoder.getClass()
                .getMethod("decode", int.class, boolean.class, boolean.class)
                .invoke(decoder, 0, false, false);
    }

    private static int resultInt(Object result, String name) throws Exception {
        return (Integer) result.getClass().getMethod("get" + name).invoke(result);
    }

    private static java.nio.ByteBuffer resultImage(Object result) throws Exception {
        return (java.nio.ByteBuffer) result.getClass().getMethod("getImage").invoke(result);
    }

    public static boolean isAvif(File file) {
        if (file == null) {
            return false;
        }
        String name = file.getName().toLowerCase();
        return name.endsWith(".avif") || name.endsWith(".avifs");
    }

    /** Reads intrinsic dimensions for any supported format, including AVIF. */
    public static boolean decodeBounds(File file, BitmapFactory.Options out) {
        if (file == null || out == null) {
            return false;
        }
        int[] cached = cachedDimensions(file);
        if (cached != null) {
            out.outWidth = cached[0];
            out.outHeight = cached[1];
            return true;
        }
        if (!isAvif(file)) {
            BitmapFactory.decodeFile(file.getAbsolutePath(), out);
            if (out.outWidth > 0 && out.outHeight > 0) {
                DIMENSIONS.put(fileKey(file), new int[] {out.outWidth, out.outHeight});
                return true;
            }
            return false;
        }
        synchronized (AVIF_LOCK) {
            try {
                NativeLibraries.load();
                int[] dimensions = nativeReadBounds(file.getAbsolutePath());
                out.outWidth = dimensions[0];
                out.outHeight = dimensions[1];
                DIMENSIONS.put(fileKey(file), dimensions);
                return true;
            } catch (Throwable t) {
                android.util.Log.w("MangaImageDecoder", "avif bounds failed", t);
                out.outWidth = -1;
                out.outHeight = -1;
                return false;
            }
        }
    }

    /** Decode with a power-of-two inSampleSize budget, matching BitmapFactory. */
    @Nullable
    public static Bitmap decodeSampled(File file, int sample,
                                       Bitmap.Config preferredConfig) {
        if (file == null) {
            return null;
        }
        if (!isAvif(file)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            if (preferredConfig != null) {
                opts.inPreferredConfig = preferredConfig;
            }
            try {
                return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            } catch (Throwable t) {
                return null;
            }
        }
        return decodeAvif(file, Math.max(1, sample), Long.MAX_VALUE);
    }

    @Nullable
    public static Bitmap decodeAvifWithinBudget(File file, long byteBudget) {
        return decodeAvif(file, 1, byteBudget);
    }

    private static Bitmap decodeAvif(File file, int sample, long byteBudget) {
        Bitmap disk = readDiskCache(file, sample, byteBudget);
        if (disk != null) {
            return disk;
        }
        // Foreground pages, preloads and covers must not overlap full native AV1 decodes.
        boolean priority = AVIF_PRIORITY.get();
        if (priority) {
            sForegroundAvifWaiters.incrementAndGet();
        }
        try {
            synchronized (AVIF_LOCK) {
                // A preload starting while the user waits for a page would add
                // its whole multi-second decode to the black-screen time.
                // Yield; the preload window retries on its next pass.
                if (!priority && sForegroundAvifWaiters.get() > 0) {
                    return null;
                }
                return decodeAvifLocked(file, sample, byteBudget);
            }
        } finally {
            if (priority) {
                sForegroundAvifWaiters.decrementAndGet();
            }
        }
    }

    public static void setDiskCacheDir(File dir) {
        sDiskCacheDir = dir;
    }

    private static File diskCacheFile(File file, int sample, long byteBudget) {
        File dir = sDiskCacheDir;
        if (dir == null) {
            return null;
        }
        String key = fileKey(file) + ":" + sample + ":" + byteBudget;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                name.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return new File(dir, name + ".jpg");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap readDiskCache(File file, int sample, long byteBudget) {
        File cached = diskCacheFile(file, sample, byteBudget);
        if (cached == null || !cached.isFile()) {
            return null;
        }
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(cached.getAbsolutePath());
            if (bitmap != null) {
                cached.setLastModified(System.currentTimeMillis());
                android.util.Log.i("MangaImageDecoder", "avif disk cache hit " + file.getName());
                return bitmap;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void writeDiskCache(File file, int sample, long byteBudget, Bitmap bitmap) {
        File target = diskCacheFile(file, sample, byteBudget);
        // JPEG cannot keep alpha; transparent pages must stay on the native path.
        if (target == null || bitmap == null || bitmap.isRecycled() || bitmap.hasAlpha()) {
            return;
        }
        DISK_WRITER.execute(() -> {
            java.io.FileOutputStream out = null;
            File tmp = new File(target.getParentFile(),
                    target.getName() + "." + Thread.currentThread().getId() + ".tmp");
            try {
                if (bitmap.isRecycled()) {
                    return;
                }
                File dir = target.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    return;
                }
                out = new java.io.FileOutputStream(tmp);
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
                out.close();
                out = null;
                if (!tmp.renameTo(target)) {
                    tmp.delete();
                    return;
                }
                evictDiskCache(dir);
            } catch (Throwable ignored) {
                // A recycled bitmap or full disk must never break decoding.
            } finally {
                if (out != null) {
                    try { out.close(); } catch (Throwable ignored) { }
                }
                if (tmp.isFile()) {
                    tmp.delete();
                }
            }
        });
    }

    private static void evictDiskCache(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long total = 0;
        for (File f : files) {
            total += f.length();
        }
        if (total <= DISK_CACHE_MAX) {
            return;
        }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= DISK_CACHE_MAX * 3 / 4) {
                break;
            }
            long len = f.length();
            if (f.delete()) {
                total -= len;
            }
        }
    }

    private static Bitmap decodeAvifLocked(File file, int sample, long byteBudget) {
        java.io.InputStream in = null;
        Object decoder = null;
        long started = android.os.SystemClock.uptimeMillis();
        try {
            NativeLibraries.load();
            in = new java.io.FileInputStream(file);
            decoder = createNativeDecoder(in);
            boolean hdr = (Boolean) decoder.getClass().getMethod("isHdr").invoke(decoder);
            if (!hdr) {
                closeNativeDecoder(decoder);
                decoder = null;
                in.close();
                in = null;
                int[] dimensions = new int[2];
                Bitmap bitmap = nativeDecodeSdr(file.getAbsolutePath(), sample, byteBudget,
                        dimensions);
                DIMENSIONS.put(fileKey(file), dimensions);
                android.util.Log.i("MangaImageDecoder", "avif sampled "
                        + dimensions[0] + "x" + dimensions[1] + " -> "
                        + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " bytes=" + bitmap.getAllocationByteCount()
                        + " alpha=" + bitmap.hasAlpha()
                        + " ms=" + (android.os.SystemClock.uptimeMillis() - started));
                writeDiskCache(file, sample, byteBudget, bitmap);
                return bitmap;
            }
            Object result = decodeNative(decoder);
            int w = resultInt(result, "Width");
            int h = resultInt(result, "Height");
            java.nio.ByteBuffer buffer = resultImage(result);
            buffer.rewind();
            long pixels = (long) w * h;
            int bytesPerPixel = buffer.remaining() == pixels * 8L ? 8 : 4;
            if (w <= 0 || h <= 0 || buffer.remaining() != pixels * bytesPerPixel) {
                throw new IllegalStateException("Unexpected AVIF pixel buffer size");
            }
            DIMENSIONS.put(fileKey(file), new int[] {w, h});
            while (Math.max(w / sample, h / sample) > 16384
                    || (long) Math.max(1, w / sample) * Math.max(1, h / sample)
                    * bytesPerPixel > byteBudget) {
                sample *= 2;
            }
            Bitmap full = Bitmap.createBitmap(w, h,
                    bytesPerPixel == 8 ? Bitmap.Config.RGBA_F16 : Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buffer);
            if (sample <= 1) {
                return full;
            }
            int sw = Math.max(1, w / sample);
            int sh = Math.max(1, h / sample);
            Bitmap scaled = Bitmap.createScaledBitmap(full, sw, sh, true);
            full.recycle();
            return scaled;
        } catch (Throwable t) {
            android.util.Log.w("MangaImageDecoder", "avif decode failed", t);
            return null;
        } finally {
            closeNativeDecoder(decoder);
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) { }
            }
        }
    }
}
