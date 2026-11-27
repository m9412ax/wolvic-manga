package com.igalia.wolvic.manga;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Detects catalogue entries that live in a "paid / members-only" section.
 *
 * <p>Many Chinese extensions expose one extra section next to the free
 * categories (COS君: {@code /zhuanqu}, others: {@code /vip}) whose pages still
 * cost coins after logging in. Those pages parse into a handful of preview
 * images, so browsing them looks broken and they can never be read in full.
 *
 * <p>When the user turns the section off (the default), matching items are
 * removed from the browse / search / latest lists. Detection is done on the
 * item URL path because that is the only stable signal every extension gives
 * us; titles are user data and are not matched.
 */
public final class MangaPaidFilter {

    /** Path segments that mean "paid area" across the usual Chinese sites. */
    private static final String[] TOKENS = {
            "vip", "svip", "vipzone", "zhuanqu", "zhuanxiang",
            "paid", "premium", "fufei", "huiyuan",
    };

    private MangaPaidFilter() { }

    /**
     * @param url           manga/chapter URL as the extension reported it
     * @param extraKeywords user supplied keywords, comma separated (may be null)
     * @return true when the entry belongs to a paid section
     */
    public static boolean isPaid(String url, String extraKeywords) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String path = pathOf(url).toLowerCase(Locale.ROOT);
        if (path.isEmpty()) {
            return false;
        }
        List<String> words = new ArrayList<>();
        for (String token : TOKENS) {
            words.add(token);
        }
        if (extraKeywords != null) {
            for (String raw : extraKeywords.split("[,，;；\\s]+")) {
                String word = raw.trim().toLowerCase(Locale.ROOT);
                if (!word.isEmpty()) {
                    words.add(word);
                }
            }
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            for (String word : words) {
                if (segment.equals(word) || segment.startsWith(word + "-")
                        || segment.startsWith(word + "_")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** "/zhuanqu/839265" for "https://site.com/zhuanqu/839265?x=1". */
    private static String pathOf(String url) {
        String rest = url;
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
            int slash = rest.indexOf('/');
            rest = slash >= 0 ? rest.substring(slash) : "";
        }
        int cut = rest.indexOf('?');
        if (cut >= 0) {
            rest = rest.substring(0, cut);
        }
        cut = rest.indexOf('#');
        if (cut >= 0) {
            rest = rest.substring(0, cut);
        }
        return rest;
    }
}
