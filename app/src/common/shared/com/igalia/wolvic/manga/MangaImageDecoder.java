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
    /** Largest full-resolution AVIF frame decoded in one go (~160 MB of ARGB).
     *  Below this we decode 1:1 and shrink with our own box-ish filter instead
     *  of trusting the native sampler. */
    private static final long AVIF_FULL_DECODE_BYTES = 160L * 1024 * 1024;
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

    /** Background probe so the reader never calls BitmapFactory on the UI
     *  thread. A 65-page SMB folder used to decode every page's bounds inside
     *  showPage() and froze the widget for 5 s+ (system logged
     *  topResumeLossTimeout). Now the size is fetched off the UI thread and the
     *  caller is pinged once it is cached. */
    private static final java.util.concurrent.ExecutorService DIM_PROBE =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "manga-dim-probe");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                t.setDaemon(true);
                return t;
            });
    private static final java.util.Set<String> DIM_INFLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final android.os.Handler DIM_MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** Fetches the image size in the background (deduplicated per file) and
     *  calls [onDone] on the main thread once it is cached. Never blocks the
     *  caller. */
    public static void requestDimensions(@Nullable final File file,
                                         @Nullable final Runnable onDone) {
        if (file == null) return;
        final String key = fileKey(file);
        if (DIMENSIONS.get(key) != null) return;
        if (!DIM_INFLIGHT.add(key)) return;
        DIM_PROBE.execute(() -> {
            try {
                if (DIMENSIONS.get(key) == null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    decodeBounds(file, o);
                }
            } catch (Throwable ignored) {
                // Unknown size is handled by the caller (single-page fallback).
            } finally {
                DIM_INFLIGHT.remove(key);
                if (onDone != null) DIM_MAIN.post(onDone);
            }
        });
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
            return new File(dir, name + ".png");
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
                // LOSSLESS on purpose. JPEG q90 put ringing ("mosquito noise")
                // around every glyph, which is what a re-read of a cached page
                // showed as jittering pixels along the text. Manga line art
                // compresses well as PNG, so the size cost is modest.
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
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

    /** Downscales [src] with a REAL filter.
     *
     *  A single bilinear pass over a big ratio (createScaledBitmap(src, w/4,
     *  h/4, true)) only reads a 2x2 neighbourhood of every output pixel and
     *  throws the rest away. On manga that is text: the thin strokes alias into
     *  a shimmering mesh, the glyphs blur into each other, and no amount of
     *  zooming brings them back. Halving repeatedly keeps every source pixel in
     *  the average, which is what makes downscaled text stay readable.
     *
     *  Never recycles [src]. */
    public static Bitmap scaleFiltered(Bitmap src, int dstW, int dstH) {
        if (src == null || src.isRecycled() || dstW <= 0 || dstH <= 0) {
            return src;
        }
        Bitmap cur = src;
        int w = src.getWidth();
        int h = src.getHeight();
        try {
            while (w / 2 >= dstW && h / 2 >= dstH) {
                int nw = Math.max(dstW, w / 2);
                int nh = Math.max(dstH, h / 2);
                Bitmap next = Bitmap.createScaledBitmap(cur, nw, nh, true);
                if (next == null || next == cur) {
                    break;
                }
                if (cur != src) {
                    cur.recycle();
                }
                cur = next;
                w = nw;
                h = nh;
            }
            if (w != dstW || h != dstH) {
                Bitmap last = Bitmap.createScaledBitmap(cur, dstW, dstH, true);
                if (last != null && last != cur) {
                    if (cur != src) {
                        cur.recycle();
                    }
                    cur = last;
                }
            }
        } catch (Throwable t) {
            // Out of memory / recycled source: the caller keeps what it had.
            return cur == src ? src : cur;
        }
        return cur;
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
                // Decode large frames at native resolution and shrink HERE with
                // scaleFiltered(). The native sampler gave no guarantee about
                // its filter, and a neighbouring-pixel shrink aliases dense
                // text into shimmering pixels - the "文字抖动 / 放大也抖" report.
                // Pick the decode scale from the byte budget OURSELVES so the
                // cost of a page is predictable: never bigger than the budget
                // (a full 1:1 frame of a huge page is what made the reader sit
                // on a black screen for seconds), never smaller than needed.
                int decodeSample = sample;
                int[] bounds = null;
                try {
                    bounds = nativeReadBounds(file.getAbsolutePath());
                    if (bounds[0] > 0 && bounds[1] > 0) {
                        long budget = byteBudget > 0 && byteBudget < Long.MAX_VALUE
                                ? byteBudget : AVIF_FULL_DECODE_BYTES;
                        long cap = Math.min(budget, AVIF_FULL_DECODE_BYTES);
                        long full = (long) bounds[0] * bounds[1] * 4L;
                        int s = 1;
                        while (s < 8 && full / (4L * s * s) > cap) {
                            s *= 2;
                        }
                        decodeSample = Math.max(s, sample);
                    }
                } catch (Throwable ignored) {
                    // Keep the caller's sample; the payload cap below still runs.
                }
                int[] dimensions = new int[2];
                Bitmap bitmap = nativeDecodeSdr(file.getAbsolutePath(), decodeSample,
                        byteBudget, dimensions);
                if (bitmap == null) {
                    return null;
                }
                // Record the SOURCE size: aspect ratio and the "is a detail
                // pass worth it" test both compare against it.
                DIMENSIONS.put(fileKey(file), bounds != null && bounds[0] > 0
                        ? bounds
                        : new int[] {bitmap.getWidth(), bitmap.getHeight()});
                // Payload cap: GPU texture edge AND the per-page byte budget.
                final int maxEdge = 16384;
                long bw = bitmap.getWidth();
                long bh = bitmap.getHeight();
                if (Math.max(bw, bh) > maxEdge || bw * bh * 4L > byteBudget) {
                    double ratio = Math.min((double) maxEdge / Math.max(bw, bh),
                            Math.sqrt((double) byteBudget / (bw * bh * 4.0)));
                    int tw = Math.max(1, (int) (bw * ratio));
                    int th = Math.max(1, (int) (bh * ratio));
                    Bitmap scaled = scaleFiltered(bitmap, tw, th);
                    if (scaled != null && scaled != bitmap) {
                        bitmap.recycle();
                        bitmap = scaled;
                    }
                }
                android.util.Log.i("MangaImageDecoder", "avif sampled "
                        + dimensions[0] + "x" + dimensions[1] + " -> "
                        + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " sample=" + decodeSample
                        + " bytes=" + bitmap.getAllocationByteCount()
                        + " alpha=" + bitmap.hasAlpha()
                        + " ms=" + (android.os.SystemClock.uptimeMillis() - started));
                // Keyed by the REQUESTED sample (what readDiskCache() will ask
                // for), even though we may have decoded 1:1 and scaled here.
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
            Bitmap scaled = scaleFiltered(full, sw, sh);
            if (scaled != full) {
                full.recycle();
            }
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
