/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.media.MediaDataSource;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Lazy MediaDataSource backed by a 115 HTTP direct link with Range support.
 * Construction does NO network. The direct URL is resolved on first getSize /
 * readAt (off the UI thread, inside MediaPlayer's decoder thread). Each readAt
 * issues a Range GET, so MediaPlayer can seek / fast-forward without ever
 * downloading the whole video up front.
 */
public final class HttpVideoDataSource extends MediaDataSource {

    private static final String TAG = "HttpVideoDS";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final String cookie;
    private final String fileId;   // pickcode or fid
    private String url;
    private long size = -1L;

    public HttpVideoDataSource(String cookie, String fileId) {
        this.cookie = cookie;
        this.fileId = fileId;
    }

    private synchronized boolean ensureUrl() {
        if (url != null) return true;
        url = Manga115Api.resolveDirectUrl(cookie, fileId);
        if (url == null) {
            Log.e(TAG, "cannot resolve direct url for " + fileId);
            return false;
        }
        Log.i(TAG, "resolved " + fileId + " -> " + url.split("\\?")[0]);
        return true;
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
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }
        return conn;
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int length) {
        if (!ensureUrl()) return -1;
        HttpURLConnection conn = null;
        try {
            conn = openRange(position, length);
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) {
                Log.w(TAG, "range GET code=" + code + " pos=" + position);
                return -1;
            }
            // 206 gives only the requested slice; 200 means the server ignored
            // the Range header - skip to [position] before copying.
            if (size < 0) {
                String cr = conn.getHeaderField("Content-Range");
                long total = conn.getContentLengthLong();
                if (cr != null && cr.contains("/")) {
                    try { total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1)); }
                    catch (Exception ignored) { }
                }
                if (code == 206) size = total;
            }
            int read = 0;
            try (InputStream in = conn.getInputStream()) {
                if (code == 200) {
                    long skipped = 0;
                    while (skipped < position) {
                        long s = in.skip(position - skipped);
                        if (s <= 0) break;
                        skipped += s;
                    }
                    if (skipped < position) return -1;
                }
                while (read < length) {
                    int n = in.read(buffer, offset + read, length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            return read == 0 ? -1 : read;
        } catch (Throwable e) {
            Log.w(TAG, "readAt pos=" + position + " len=" + length + ": " + e.getMessage());
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public synchronized long getSize() {
        if (!ensureUrl()) return -1L;
        if (size > 0) return size;
        HttpURLConnection conn = null;
        try {
            conn = openRange(0, 1);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            String cr = conn.getHeaderField("Content-Range");
            if (cr != null && cr.contains("/")) {
                size = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1));
            } else if (code == 200) {
                size = conn.getContentLengthLong();
            }
            conn.disconnect();
        } catch (Throwable e) {
            Log.w(TAG, "getSize failed: " + e.getMessage());
        }
        return size;
    }

    @Override
    public synchronized void close() {
        // Each readAt opens/closes its own connection; nothing persistent here.
    }
}
