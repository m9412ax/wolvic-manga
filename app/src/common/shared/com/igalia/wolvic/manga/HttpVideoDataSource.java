/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Blocking HTTP-Range byte reader for PrefetchVideoDataSource (115 online
 * video). readAt() is called ONLY from the prefetch feeder thread, which asks
 * for large contiguous chunks - so one Range GET feeds megabytes, not one
 * connection per tiny decoder read (the cause of the old stutter).
 */
public final class HttpVideoDataSource implements PrefetchVideoDataSource.Reader {

    private static final String TAG = "HttpVideoDS";
    private static final String UA = "Mozilla/5.0 115Browser/27.0.5.7";

    private final String cookie;
    private final String fileId;
    private String url;
    private String cdnCookie;
    private long size = -1L;
    private final Object urlLock = new Object();

    public HttpVideoDataSource(String cookie, String fileId) {
        this.cookie = cookie;
        this.fileId = fileId;
    }

    private boolean ensureUrl() {
        synchronized (urlLock) {
            if (url != null) return true;
            Manga115Api.ResolvedLink link = Manga115Api.resolveLink(cookie, fileId);
            if (link == null) {
                Log.e(TAG, "cannot resolve direct url for " + fileId);
                return false;
            }
            url = link.url;
            cdnCookie = !link.cdnCookie.isEmpty() ? link.cdnCookie : cookie;
            Log.i(TAG, "resolved " + fileId + " -> " + url.split("\\?")[0]
                    + " cdnCookie=" + (cdnCookie != null ? "yes" : "no"));
            return true;
        }
    }

    private HttpURLConnection openRange(long position, int length) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", "https://115.com/");
        conn.setRequestProperty("Range", "bytes=" + position + "-"
                + (position + Math.max(0, length - 1)));
        String useCookie = (cdnCookie != null && !cdnCookie.isEmpty()) ? cdnCookie : cookie;
        if (useCookie != null && !useCookie.isEmpty()) {
            conn.setRequestProperty("Cookie", useCookie);
        }
        return conn;
    }

    @Override
    public long size() {
        if (!ensureUrl()) return -1L;
        if (size > 0) return size;
        HttpURLConnection conn = null;
        try {
            conn = openRange(0, 1024);
            int code = conn.getResponseCode();
            String cr = conn.getHeaderField("Content-Range");
            if (cr != null && cr.contains("/")) {
                size = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1));
            } else if (code == 200) {
                size = conn.getContentLengthLong();
            }
        } catch (Throwable e) {
            Log.w(TAG, "size failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return size;
    }

    @Override
    public int readAt(long position, byte[] buf, int off, int len) {
        if (!ensureUrl()) return -1;
        HttpURLConnection conn = null;
        try {
            conn = openRange(position, len);
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) {
                Log.w(TAG, "range GET code=" + code + " pos=" + position);
                return -1;
            }
            int read = 0;
            try (InputStream in = conn.getInputStream()) {
                if (code == 200) {
                    // Server ignored Range: skip to requested position.
                    long skipped = 0;
                    while (skipped < position) {
                        long s = in.skip(position - skipped);
                        if (s <= 0) break;
                        skipped += s;
                    }
                    if (skipped < position) return -1;
                }
                while (read < len) {
                    int n = in.read(buf, off + read, len - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            return read > 0 ? read : -1;
        } catch (Throwable e) {
            Log.w(TAG, "readAt pos=" + position + " len=" + len + ": " + e.getMessage());
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public void close() {
        // Each readAt opens/closes its own connection; nothing persistent.
    }
}
