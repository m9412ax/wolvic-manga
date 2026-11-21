/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import java.util.Comparator;

/** Human ("natural") string ordering: "page2" < "page10", case-insensitive.
 *  Lives in the manga package so both local, SMB and 115 sources can use it
 *  without depending on the UI adapter layer. */
public final class NaturalOrder {

    public static final Comparator<String> INSTANCE = NaturalOrder::compare;

    private NaturalOrder() {}

    public static int compare(String a, String b) {
        final String sa = a == null ? "" : a;
        final String sb = b == null ? "" : b;
        int ia = 0, ib = 0;
        while (ia < sa.length() && ib < sb.length()) {
            char ca = sa.charAt(ia);
            char cb = sb.charAt(ib);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ja = ia;
                while (ja < sa.length() && Character.isDigit(sa.charAt(ja))) ja++;
                int jb = ib;
                while (jb < sb.length() && Character.isDigit(sb.charAt(jb))) jb++;
                int cmp = compareNumeric(sa.substring(ia, ja), sb.substring(ib, jb));
                if (cmp != 0) return cmp;
                ia = ja;
                ib = jb;
            } else {
                int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (cmp != 0) return cmp;
                ia++;
                ib++;
            }
        }
        return Integer.compare(sa.length() - ia, sb.length() - ib);
    }

    private static int compareNumeric(String a, String b) {
        String na = stripLeadingZeros(a);
        String nb = stripLeadingZeros(b);
        if (na.length() != nb.length()) return Integer.compare(na.length(), nb.length());
        return na.compareTo(nb);
    }

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') i++;
        return s.substring(i);
    }
}
