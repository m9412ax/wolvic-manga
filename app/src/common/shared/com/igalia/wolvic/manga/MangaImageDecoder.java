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
        ca.mpreg.imagedecoder.ImageDecoder decoder = null;
        try {
            in = new java.io.FileInputStream(file);
            decoder = ca.mpreg.imagedecoder.ImageDecoder.Companion.new(in);
            ca.mpreg.imagedecoder.ImageDecoder.DecodeResult result = decoder.decode();
            out.outWidth = result.getWidth();
            out.outHeight = result.getHeight();
            return out.outWidth > 0 && out.outHeight > 0;
        } catch (Throwable t) {
            android.util.Log.w("MangaImageDecoder", "avif bounds failed: " + t);
            out.outWidth = -1;
            out.outHeight = -1;
            return false;
        } finally {
            if (decoder != null) {
                try { decoder.close(); } catch (Throwable ignored) { }
            }
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
        ca.mpreg.imagedecoder.ImageDecoder decoder = null;
        try {
            in = new java.io.FileInputStream(file);
            decoder = ca.mpreg.imagedecoder.ImageDecoder.Companion.new(in);
            ca.mpreg.imagedecoder.ImageDecoder.DecodeResult result = decoder.decode();
            int w = result.getWidth();
            int h = result.getHeight();
            java.nio.ByteBuffer buffer = result.getImage();
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
            if (decoder != null) {
                try { decoder.close(); } catch (Throwable ignored) { }
            }
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) { }
            }
        }
    }
}
