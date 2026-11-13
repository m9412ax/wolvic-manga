/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.util.Base64;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A small but functional WebDAV client used by the "网盘" manga page.
 * PROPFIND Depth:1 lists a collection and GET streams a file to local storage.
 * No third-party dependency is required.
 */
public final class MangaWebDav {

    private static final String TAG = "MangaWebDav";
    private static final int TIMEOUT = 20000;

    public static final class Entry {
        public final String href;
        public final String name;
        public final boolean directory;
        public final long size;

        Entry(String href, String name, boolean directory, long size) {
            this.href = href;
            this.name = name;
            this.directory = directory;
            this.size = size;
        }
    }

    private MangaWebDav() {}

    private static String normalize(String url) {
        String value = url == null ? "" : url.trim();
        if (!value.endsWith("/")) {
            value += "/";
        }
        return value;
    }

    private static void applyAuth(HttpURLConnection connection, String user, String pass) {
        if (user == null || user.isEmpty()) {
            return;
        }
        String credentials = user + ":" + (pass == null ? "" : pass);
        String encoded = Base64.encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        connection.setRequestProperty("Authorization", "Basic " + encoded);
    }

    private static void forceMethod(HttpURLConnection connection, String method) throws Exception {
        try {
            connection.setRequestMethod(method);
        } catch (java.net.ProtocolException e) {
            // Some HttpURLConnection implementations whitelist request methods.
            // Walk the real implementation class hierarchy to find the field.
            Class<?> type = connection.getClass();
            boolean patched = false;
            while (type != null) {
                try {
                    Field field = type.getDeclaredField("method");
                    field.setAccessible(true);
                    field.set(connection, method);
                    patched = true;
                    break;
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
            if (!patched) {
                throw e;
            }
        }
    }

    /** Returns direct children of a WebDAV collection. Call from a background thread. */
    public static List<Entry> list(String baseUrl, String user, String pass) throws Exception {
        String root = normalize(baseUrl);
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<D:propfind xmlns:D=\"DAV:\"><D:prop>"
                + "<D:resourcetype/><D:getcontentlength/><D:displayname/>"
                + "</D:prop></D:propfind>";

        HttpURLConnection connection = (HttpURLConnection) new URL(root).openConnection();
        try {
            forceMethod(connection, "PROPFIND");
            connection.setRequestProperty("Depth", "1");
            connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8");
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);
            applyAuth(connection, user, pass);
            connection.setDoOutput(true);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("PROPFIND HTTP " + code);
            }
            String xml;
            try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                xml = readAll(in);
            }
            return parse(xml, root);
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder builder = new StringBuilder();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static List<Entry> parse(String xml, String root) throws Exception {
        List<Entry> entries = new ArrayList<>();
        String rootPath;
        try {
            rootPath = new URL(root).getPath();
        } catch (Exception e) {
            rootPath = root;
        }
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(xml));
        int event = parser.getEventType();
        String href = null;
        boolean collection = false;
        long size = 0;
        // XmlPullParser.nextText() reliably consumes the text of a simple tag;
        // matching local names keeps DAV:/d:/D: namespace prefixes irrelevant.
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = localName(parser.getName());
                if ("response".equals(name)) {
                    href = null;
                    collection = false;
                    size = 0;
                } else if ("href".equals(name)) {
                    href = parser.nextText();
                } else if ("collection".equals(name)) {
                    collection = true;
                } else if ("getcontentlength".equals(name)) {
                    try {
                        size = Long.parseLong(parser.nextText().trim());
                    } catch (Exception ignored) {
                        size = 0;
                    }
                }
            } else if (event == XmlPullParser.END_TAG
                    && "response".equals(localName(parser.getName()))) {
                if (href != null && !href.isEmpty()) {
                    String decoded;
                    try {
                        decoded = URLDecoder.decode(href, "UTF-8");
                    } catch (Exception e) {
                        decoded = href;
                    }
                    if (!samePath(decoded, root) && !samePath(decoded, rootPath)) {
                        String clean = decoded.endsWith("/")
                                ? decoded.substring(0, decoded.length() - 1) : decoded;
                        int slash = clean.lastIndexOf('/');
                        String label = slash >= 0 ? clean.substring(slash + 1) : clean;
                        if (!label.isEmpty()) {
                            entries.add(new Entry(decoded, label, collection, size));
                        }
                    }
                }
            }
            event = parser.next();
        }
        return entries;
    }

    private static String localName(String value) {
        if (value == null) {
            return "";
        }
        int colon = value.indexOf(':');
        return colon >= 0 ? value.substring(colon + 1) : value;
    }

    private static boolean samePath(String a, String b) {
        String left = a.endsWith("/") ? a : a + "/";
        String right = b.endsWith("/") ? b : b + "/";
        return left.equals(right);
    }

    /** Streams a remote file into destination. Call from a background thread. */
    public static long download(String fileUrl, String user, String pass,
                                File destination, ProgressListener listener) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(fileUrl).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);
            applyAuth(connection, user, pass);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("GET HTTP " + code);
            }
            File parent = destination.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            long total = connection.getContentLength();
            long written = 0;
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new FileOutputStream(destination)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    written += read;
                    if (listener != null) {
                        listener.onProgress(written, total);
                    }
                }
            }
            return written;
        } finally {
            connection.disconnect();
        }
    }

    public static String resolve(String baseUrl, String href) throws Exception {
        if (href == null || href.isEmpty()) {
            return normalize(baseUrl);
        }
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        if (href.startsWith("/")) {
            URL parsed = new URL(normalize(baseUrl));
            return parsed.getProtocol() + "://" + parsed.getAuthority() + href;
        }
        return new URL(new URL(normalize(baseUrl)), href).toString();
    }

    public interface ProgressListener {
        void onProgress(long written, long total);
    }

    public static void log(String message) {
        Log.i(TAG, message);
    }
}
