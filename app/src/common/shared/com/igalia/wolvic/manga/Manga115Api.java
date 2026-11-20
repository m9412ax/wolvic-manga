/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Cookie-based 115 netdisk client. Only the LOGIN (QR code) part lives here;
 * browsing/direct links are added in a later stage. 115's open platform is
 * discontinued, so the web cookie obtained by scanning a QR code is the only
 * working login. All methods BLOCK - call from a background thread.
 */
public final class Manga115Api {

    private static final String TAG = "Manga115Api";

    /** Result of the first QR step: data needed to render + poll the code. */
    public static final class QrSession {
        public final String uid;
        public final String time;
        public final String sign;
        public final String qrContent;   // text to encode into the QR bitmap

        QrSession(String uid, String time, String sign, String qrContent) {
            this.uid = uid;
            this.time = time;
            this.sign = sign;
            this.qrContent = qrContent;
        }
    }

    public static final int STATUS_WAITING = 0;
    public static final int STATUS_SCANNED = 1;
    public static final int STATUS_CONFIRMED = 2;
    public static final int STATUS_FAILED = -1;

    private Manga115Api() {}

    /** Step 1: request a fresh QR token. */
    public static QrSession startQr() throws Exception {
        String json = httpGet("https://qrcodeapi.115.com/api/1.0/web/1.0/token/", null);
        JSONObject root = new JSONObject(json);
        int code = root.optInt("code", -1);
        if (code != 0 || !root.has("data")) {
            throw new IllegalStateException("获取二维码失败 code=" + code);
        }
        JSONObject data = root.getJSONObject("data");
        String uid = data.optString("uid", "");
        String time = data.optString("time", "");
        String sign = data.optString("sign", "");
        String qr = data.optString("qrcode", "");
        if (uid.isEmpty() || qr.isEmpty()) {
            throw new IllegalStateException("二维码响应缺少 uid/qrcode");
        }
        Log.i(TAG, "QR session uid=" + uid);
        return new QrSession(uid, time, sign, qr);
    }

    /** Step 2: poll scan status. Returns STATUS_*; unknown codes mean WAITING. */
    public static int pollQr(QrSession session) throws Exception {
        String url = "https://qrcodeapi.115.com/get/status/?uid=" + enc(session.uid)
                + "&time=" + enc(session.time)
                + "&sign=" + enc(session.sign)
                + "&_=" + (System.currentTimeMillis() / 1000L);
        String json = httpGet(url, null);
        JSONObject root = new JSONObject(json);
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            return STATUS_WAITING;
        }
        int status = data.optInt("status", STATUS_WAITING);
        // Only treat explicit failure codes as failure; anything unenumerated
        // is treated as "still waiting" (matches the successful Quark pattern).
        if (status == STATUS_CONFIRMED) return STATUS_CONFIRMED;
        if (status == -1 || status == -2) return STATUS_FAILED;
        if (status == STATUS_SCANNED) return STATUS_SCANNED;
        return STATUS_WAITING;
    }

    /** Step 3: exchange the confirmed uid for a login cookie. Tries both
     *  known endpoints because the upstream link has changed before. */
    public static String exchangeCookie(QrSession session, String device) throws Exception {
        if (device == null || device.isEmpty()) {
            device = "tv";
        }
        String form = "app=" + enc(device) + "&account=" + enc(session.uid);
        String[] hosts = {
                "https://passportapi.115.com/app/1.0/" + device + "/1.0/login/qrcode/",
                "https://qrcodeapi.115.com/app/1.0/" + device + "/1.0/login/qrcode/",
        };
        Exception last = null;
        for (String url : hosts) {
            try {
                String json = httpPost(url, form, null);
                JSONObject root = new JSONObject(json);
                JSONObject data = root.optJSONObject("data");
                if (data == null) throw new IllegalStateException("无 data: " + json);
                JSONObject cookieObj = data.optJSONObject("cookie");
                if (cookieObj == null) {
                    // Some responses put fields at data level.
                    cookieObj = data;
                }
                String cookie = buildCookie(cookieObj);
                if (cookie.isEmpty()) {
                    throw new IllegalStateException("cookie 字段为空");
                }
                Log.i(TAG, "QR cookie obtained via " + hostOf(url));
                return cookie;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "exchange via " + hostOf(url) + " failed: " + e.getMessage());
            }
        }
        throw (last != null) ? last : new IllegalStateException("换取 cookie 失败");
    }

    /** Builds canonical "UID=..; CID=..; SEID=..; KID=.." from a JSON object. */
    private static String buildCookie(JSONObject obj) {
        String[] keys = {"UID", "CID", "SEID", "KID"};
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            String v = obj.optString(k, "");
            if (!v.isEmpty()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(k).append('=').append(v);
            }
        }
        return sb.toString();
    }

    /** Returns true if the cookie is still valid. errno 990001 = expired. */
    public static boolean validate(String cookie) {
        if (cookie == null || cookie.isEmpty()) return false;
        try {
            String json = httpGet("https://webapi.115.com/user/info?ct=json", cookie);
            JSONObject root = new JSONObject(json);
            int errno = root.optInt("errno", -1);
            return errno == 0;
        } catch (Exception e) {
            Log.w(TAG, "validate failed: " + e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------- HTTP

    private static String httpGet(String url, String cookie) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " " + url);
            }
            return readAll(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static String httpPost(String url, String form, String cookie) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }
        try {
            byte[] body = form.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " " + url);
            }
            return readAll(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String enc(String value) {
        // Android's runtime (core-oj) only has encode(String, String). The
        // encode(String, Charset) overload is Java 10+ and throws NoSuchMethodError
        // at runtime even though compileSdk lets it compile. Always use the
        // String overload and catch the checked exception here.
        String v = value == null ? "" : value;
        try {
            return URLEncoder.encode(v, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            // "UTF-8" is guaranteed on Android; this can never actually happen.
            return v;
        }
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }
}
