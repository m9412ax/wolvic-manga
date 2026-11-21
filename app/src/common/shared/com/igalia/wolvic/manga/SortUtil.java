/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import java.util.Comparator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Shared sorting for local / SMB / 115 listings.
 *  Mode: 0 name asc, 1 name desc, 2 date newest first, 3 date oldest first. */
public final class SortUtil {

    private SortUtil() {}

    /** Generic comparator over an item with a name and a date. */
    public static <T> Comparator<T> comparator(int mode,
                                               java.util.function.Function<T, String> name,
                                               java.util.function.ToLongFunction<T> dateMs) {
        switch (mode) {
            case 1:
                return (a, b) -> NaturalOrder.compare(name.apply(b), name.apply(a));
            case 2:
                return (a, b) -> {
                    int c = Long.compare(dateMs.applyAsLong(b), dateMs.applyAsLong(a));
                    return c != 0 ? c : NaturalOrder.compare(name.apply(a), name.apply(b));
                };
            case 3:
                return (a, b) -> {
                    int c = Long.compare(dateMs.applyAsLong(a), dateMs.applyAsLong(b));
                    return c != 0 ? c : NaturalOrder.compare(name.apply(a), name.apply(b));
                };
            case 0:
            default:
                return (a, b) -> NaturalOrder.compare(name.apply(a), name.apply(b));
        }
    }

    /** Label shown on the sort button for the current mode. Clicking cycles. */
    public static String label(int mode) {
        switch (mode) {
            case 1: return "名称 ↓（Z→A）";
            case 2: return "日期 ↓（新→旧）";
            case 3: return "日期 ↑（旧→新）";
            case 0:
            default: return "名称 ↑（A→Z）";
        }
    }

    /**
     * Comparator for a mixed folder listing. Enforces, in order:
     *   1) directories before files
     *   2) videos before images (only when [videoFirst] is true)
     *   3) the shared name/date sort mode within each group
     * This is used identically by the LOCAL / SMB / 115 file browsers so all
     * three behave the same way.
     */
    public static <T> Comparator<T> listingComparator(
            int mode, boolean videoFirst,
            Function<T, Boolean> isDir,
            Predicate<T> isVideo,
            Function<T, String> name,
            ToLongFunction<T> dateMs) {
        final Comparator<T> base = comparator(mode, name, dateMs);
        return (a, b) -> {
            boolean da = isDir.apply(a), db = isDir.apply(b);
            if (da != db) return da ? -1 : 1;
            if (videoFirst) {
                boolean va = !da && isVideo.test(a);
                boolean vb = !db && isVideo.test(b);
                if (va != vb) return va ? -1 : 1;
            }
            return base.compare(a, b);
        };
    }

    public static int next(int mode) {
        return (mode + 1) % 4;
    }
}
