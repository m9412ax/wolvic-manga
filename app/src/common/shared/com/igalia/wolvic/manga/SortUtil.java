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
 *  Mode: 0 name asc, 1 name desc, 2 date newest first, 3 date oldest first,
 *        4 size largest first, 5 size smallest first.
 *  Modes 4/5 need a size accessor; the overloads that do not receive one fall
 *  back to name order so no call site can ever produce an undefined order. */
public final class SortUtil {

    public static final int MODE_NAME_ASC = 0;
    public static final int MODE_NAME_DESC = 1;
    public static final int MODE_DATE_DESC = 2;
    public static final int MODE_DATE_ASC = 3;
    public static final int MODE_SIZE_DESC = 4;
    public static final int MODE_SIZE_ASC = 5;
    public static final int MODE_COUNT = 6;

    private SortUtil() {}

    /** Sort key index used by the quick-sheet UI: 0 title, 1 date, 2 size. */
    public static int keyOf(int mode) {
        switch (mode) {
            case MODE_DATE_DESC:
            case MODE_DATE_ASC:
                return 1;
            case MODE_SIZE_DESC:
            case MODE_SIZE_ASC:
                return 2;
            default:
                return 0;
        }
    }

    /** True when [mode] sorts ascending (A-Z, oldest first, smallest first). */
    public static boolean ascendingOf(int mode) {
        switch (mode) {
            case MODE_NAME_DESC:
            case MODE_DATE_DESC:
            case MODE_SIZE_DESC:
                return false;
            default:
                return true;
        }
    }

    /** Inverse of {@link #keyOf(int)} / {@link #ascendingOf(int)}. */
    public static int modeOf(int key, boolean ascending) {
        switch (key) {
            case 1:
                return ascending ? MODE_DATE_ASC : MODE_DATE_DESC;
            case 2:
                return ascending ? MODE_SIZE_ASC : MODE_SIZE_DESC;
            default:
                return ascending ? MODE_NAME_ASC : MODE_NAME_DESC;
        }
    }

    /** Generic comparator over an item with a name and a date. */
    public static <T> Comparator<T> comparator(int mode,
                                               Function<T, String> name,
                                               ToLongFunction<T> dateMs) {
        return comparator(mode, name, dateMs, item -> 0L);
    }

    /** Same, but able to honour the size modes (4/5). */
    public static <T> Comparator<T> comparator(int mode,
                                               Function<T, String> name,
                                               ToLongFunction<T> dateMs,
                                               ToLongFunction<T> size) {
        switch (mode) {
            case MODE_NAME_DESC:
                return (a, b) -> NaturalOrder.compare(name.apply(b), name.apply(a));
            case MODE_DATE_DESC:
                return (a, b) -> {
                    int c = Long.compare(dateMs.applyAsLong(b), dateMs.applyAsLong(a));
                    return c != 0 ? c : NaturalOrder.compare(name.apply(a), name.apply(b));
                };
            case MODE_DATE_ASC:
                return (a, b) -> {
                    int c = Long.compare(dateMs.applyAsLong(a), dateMs.applyAsLong(b));
                    return c != 0 ? c : NaturalOrder.compare(name.apply(a), name.apply(b));
                };
            case MODE_SIZE_DESC:
                return (a, b) -> {
                    int c = Long.compare(size.applyAsLong(b), size.applyAsLong(a));
                    return c != 0 ? c : NaturalOrder.compare(name.apply(a), name.apply(b));
                };
            case MODE_SIZE_ASC:
                return (a, b) -> {
                    int c = Long.compare(size.applyAsLong(a), size.applyAsLong(b));
                    return c != 0 ? c : NaturalOrder.compare(name.apply(a), name.apply(b));
                };
            case MODE_NAME_ASC:
            default:
                return (a, b) -> NaturalOrder.compare(name.apply(a), name.apply(b));
        }
    }

    /** Label shown on the sort button for the current mode. Clicking cycles. */
    public static String label(int mode) {
        switch (mode) {
            case MODE_NAME_DESC: return "名称 ↓（Z→A）";
            case MODE_DATE_DESC: return "日期 ↓（新→旧）";
            case MODE_DATE_ASC: return "日期 ↑（旧→新）";
            case MODE_SIZE_DESC: return "大小 ↓（大→小）";
            case MODE_SIZE_ASC: return "大小 ↑（小→大）";
            case MODE_NAME_ASC:
            default: return "名称 ↑（A→Z）";
        }
    }

    /**
     * Comparator for a mixed folder listing. Enforces, in order:
     *   1) directories before files (when [folderFirst] is on)
     *   2) videos before images (only when [videoFirst] is true)
     *   3) the shared name/date/size sort mode within each group
     * This is used identically by the LOCAL / SMB / 115 file browsers so all
     * three behave the same way.
     */
    public static <T> Comparator<T> listingComparator(
            int mode, boolean videoFirst,
            Function<T, Boolean> isDir,
            Predicate<T> isVideo,
            Function<T, String> name,
            ToLongFunction<T> dateMs) {
        return listingComparator(mode, true, videoFirst, isDir, isVideo, name, dateMs, item -> 0L);
    }

    /** Size-aware variant, folders still pinned first. */
    public static <T> Comparator<T> listingComparator(
            int mode, boolean videoFirst,
            Function<T, Boolean> isDir,
            Predicate<T> isVideo,
            Function<T, String> name,
            ToLongFunction<T> dateMs,
            ToLongFunction<T> size) {
        return listingComparator(mode, true, videoFirst, isDir, isVideo, name, dateMs, size);
    }

    /** Full variant: the quick-sheet can turn "folders first" off. */
    public static <T> Comparator<T> listingComparator(
            int mode, boolean folderFirst, boolean videoFirst,
            Function<T, Boolean> isDir,
            Predicate<T> isVideo,
            Function<T, String> name,
            ToLongFunction<T> dateMs,
            ToLongFunction<T> size) {
        final Comparator<T> base = comparator(mode, name, dateMs, size);
        return (a, b) -> {
            boolean da = isDir.apply(a), db = isDir.apply(b);
            if (folderFirst && da != db) return da ? -1 : 1;
            if (videoFirst) {
                boolean va = !da && isVideo.test(a);
                boolean vb = !db && isVideo.test(b);
                if (va != vb) return va ? -1 : 1;
            }
            return base.compare(a, b);
        };
    }

    public static int next(int mode) {
        return (mode + 1) % MODE_COUNT;
    }
}
