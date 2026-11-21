/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import java.util.Comparator;

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

    public static int next(int mode) {
        return (mode + 1) % 4;
    }
}
