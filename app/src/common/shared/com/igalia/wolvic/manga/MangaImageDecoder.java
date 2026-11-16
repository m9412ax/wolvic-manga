/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.os.Build;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

/**
 * Unified still-image decoder for the manga reader/covers.
 *
 * BitmapFactory cannot decode AVIF at all (decodeFile returns null with
 * outWidth=-1). ImageDecoder (API 28+, which covers every Pico runtime we
 * ship for) handles AVIF natively through the platform codec - no extra
 * native library is needed. All legacy formats keep the fast BitmapFactory
 * path so their behaviour (including inSampleSize) is unchanged.
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
        if (!isAvif(file) || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            BitmapFactory.decodeFile(file.getAbsolutePath(), out);
            return out.outWidth > 0 && out.outHeight > 0;
        }
        // Header-only size query via decodeDrawable's OnHeaderDecodedListener.
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(file);
            final int[] dims = new int[2];
            android.graphics.drawable.Drawable header = ImageDecoder.decodeDrawable(
                    source, (decoder, info, src) -> {
                        dims[0] = info.getSize().getWidth();
                        dims[1] = info.getSize().getHeight();
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        decoder.setTargetSampleSize(64); // tiny pixel copy for a size query
                    });
            if (header instanceof android.graphics.drawable.Animatable) {
                ((android.graphics.drawable.Animatable) header).stop();
            }
            out.outWidth = dims[0];
            out.outHeight = dims[1];
            return dims[0] > 0 && dims[1] > 0;
        } catch (Throwable t) {
            out.outWidth = -1;
            out.outHeight = -1;
            return false;
        }
    }

    /** Decode with a power-of-two inSampleSize budget, matching BitmapFactory. */
    @Nullable
    public static Bitmap decodeSampled(File file, int sample,
                                       Bitmap.Config preferredConfig) {
        if (file == null) {
            return null;
        }
        if (!isAvif(file) || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
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
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(file);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetSampleSize(sample);
                decoder.setTargetColorSpace(android.graphics.ColorSpace.get(
                        android.graphics.ColorSpace.Named.SRGB));
            });
        } catch (Throwable t) {
            return null;
        }
    }
}
