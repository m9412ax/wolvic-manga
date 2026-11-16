/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * Unified still-image decoder for the manga reader/covers.
 *
 * BitmapFactory / the platform ImageDecoder cannot decode AVIF on all
 * devices (notably Pico Neo3). AVIF/HEIC/JXL go through the bundled libvips
 * decoder (ca.mpreg:imagedecoder, same as Mihon); other formats keep the
 * fast BitmapFactory path so their behaviour (including inSampleSize) stays
 * unchanged.
 */
public final class MangaImageDecoder {

    private MangaImageDecoder() { }

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
        return decoder.getClass().getMethod("decode").invoke(decoder);
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
        if (!isAvif(file)) {
            BitmapFactory.decodeFile(file.getAbsolutePath(), out);
            return out.outWidth > 0 && out.outHeight > 0;
        }
        // AVIF is not decoded by the platform decoder on many devices (e.g.
        // Pico Neo3). Use the bundled libvips decoder for a header decode.
        java.io.InputStream in = null;
        Object decoder = null;
        try {
            in = new java.io.FileInputStream(file);
            decoder = createNativeDecoder(in);
            Object result = decodeNative(decoder);
            out.outWidth = resultInt(result, "Width");
            out.outHeight = resultInt(result, "Height");
            return out.outWidth > 0 && out.outHeight > 0;
        } catch (Throwable t) {
            android.util.Log.w("MangaImageDecoder", "avif bounds failed: " + t);
            out.outWidth = -1;
            out.outHeight = -1;
            return false;
        } finally {
            closeNativeDecoder(decoder);
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) { }
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
        java.io.InputStream in = null;
        Object decoder = null;
        try {
            in = new java.io.FileInputStream(file);
            decoder = createNativeDecoder(in);
            Object result = decodeNative(decoder);
            int w = resultInt(result, "Width");
            int h = resultInt(result, "Height");
            java.nio.ByteBuffer buffer = resultImage(result);
            buffer.rewind();
            Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
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
            android.util.Log.w("MangaImageDecoder", "avif decode failed: " + t);
            return null;
        } finally {
            closeNativeDecoder(decoder);
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) { }
            }
        }
    }
}
