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
        // Foreground pages, preloads and covers must not overlap full native AV1 decodes.
        synchronized (AVIF_LOCK) {
            return decodeAvifLocked(file, sample, byteBudget);
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
