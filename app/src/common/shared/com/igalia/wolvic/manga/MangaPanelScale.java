/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.content.Context;

import com.igalia.wolvic.browser.SettingsStore;
import com.igalia.wolvic.ui.widgets.WidgetPlacement;

/**
 * Works out how big the reader panel's texture has to be.
 *
 * <p>The panel is drawn in the scene (see {@code WidgetPlacement.mipmap}): the
 * widget surface is copied into a regular 2D texture whose mip chain is rebuilt
 * whenever the page changes, and the scene renderer minifies it with trilinear
 * filtering. That removes the crawl a composition layer had - a layer samples
 * an external OES texture that cannot carry mipmaps, so anything below 1:1 was
 * picked from a moving, undersampled grid.
 *
 * <p>{@code textureWidth()} is {@code width * density * textureScale}, and the
 * default {@code textureScale} (display DPI / 100) oversamples the panel by a
 * large factor - a 626dp reader panel ends up with a texture several times
 * wider than the number of screen pixels it covers. Most of that detail can
 * never be displayed, so this class derives the panel's real on-screen width
 * from its angular size (world width and distance, both known to the widget)
 * and returns a {@code textureScale} that gives the texture that many texels
 * per screen pixel: enough headroom for a sharp result, small enough to stay
 * cheap to filter.
 */
public final class MangaPanelScale {

    /** Pixels per degree of the physical panel the compositor samples from.
     *  Pico 4 renders 2160 px per eye across roughly 96 degrees horizontally. */
    private static final float PANEL_PPD = 22.5f;

    /** Wolvic keeps widget translations in units of 1/360 m (WORLD_DPI_RATIO). */
    private static final float UNITS_PER_METER = 360.0f;

    private static final float MIN_SCALE = 0.05f;
    private static final float MAX_SCALE = 4.0f;

    private MangaPanelScale() {}

    /** The texture multiplier to store in {@link WidgetPlacement#textureScale}. */
    public static float textureScale(Context aContext, WidgetPlacement aPlacement) {
        MangaSettings settings = MangaSettings.get(aContext);
        // Base value the rest of Wolvic uses; leaving it untouched keeps the
        // old behaviour when nothing is configured.
        float base = SettingsStore.getInstance(aContext).getDisplayDpi() / 100.0f;

        if (settings.integer(MangaSettings.READER_RENDER_MODE) == 1) {
            int pct = settings.integer(MangaSettings.READER_RENDER_SCALE);
            if (pct > 0 && pct < 100) {
                return clamp(base * (pct / 100.0f));
            }
            return clamp(base);
        }

        float viewPx = aPlacement.width * aPlacement.density;
        float screenPx = screenFootprintPx(aPlacement);
        if (viewPx < 2f || screenPx < 2f) {
            // Geometry not usable yet: keep Wolvic's own value rather than
            // guessing and ending up with a blurry panel.
            return clamp(base);
        }
        int mult = settings.integer(MangaSettings.READER_RENDER_MULT);
        if (mult < 1) {
            mult = 1;
        } else if (mult > 3) {
            mult = 3;
        }
        // textureWidth = viewPx * textureScale, so this lands the texture on
        // exactly `mult` times the pixels the panel covers on screen.
        return clamp(screenPx * mult / viewPx);
    }

    /** Width of the panel in real screen pixels, derived from its angular size. */
    public static float screenFootprintPx(WidgetPlacement aPlacement) {
        float worldW = aPlacement.worldWidth;
        float dist = Math.abs(aPlacement.translationZ) / UNITS_PER_METER;
        if (worldW <= 0.01f || dist <= 0.05f) {
            return 0f;
        }
        double angleDeg = Math.toDegrees(2.0 * Math.atan(worldW / (2.0 * dist)));
        return (float) (angleDeg * PANEL_PPD);
    }

    private static float clamp(float aValue) {
        if (aValue < MIN_SCALE) {
            return MIN_SCALE;
        }
        if (aValue > MAX_SCALE) {
            return MAX_SCALE;
        }
        return aValue;
    }
}
