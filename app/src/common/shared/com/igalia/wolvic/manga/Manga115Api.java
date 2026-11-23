/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    // --------------------------------------------------------- ONLINE READ

    /** A file/folder entry in a 115 directory listing. */
    public static final class DirEntry {
        public final String name;
        public final String fileId;     // file_id / fid (empty for folders in short schema)
        public final String cid;        // folder id (for drilling down)
        public final String pickCode;   // pc / pick_code (for image direct link)
        public final boolean isDir;
        public final long size;
        public final long modifiedMs;
        DirEntry(String name, String fileId, String cid, String pickCode,
                 boolean isDir, long size, long modifiedMs) {
            this.name = name; this.fileId = fileId; this.cid = cid;
            this.pickCode = pickCode; this.isDir = isDir; this.size = size;
            this.modifiedMs = modifiedMs;
        }
    }

    /** Lists children of a 115 folder. cid "0" = root. Cookie required.
     *  Compatible with both the long (name/file_id/is_dir) and short
     *  (n/fid/ico) field schemas returned by different 115 endpoints. */
    public static List<DirEntry> listDir(String cookie, String cid) throws Exception {
        if (cid == null || cid.isEmpty()) cid = "0";
        String url = "https://webapi.115.com/files?"
                + "aid=1&cid=" + enc(cid)
                + "&o=user_ptime&asc=0&offset=0&show_dir=1&limit=1000"
                + "&snap=0&natsort=1&record_open_time=1&count_folders=1&format=json";
        String json = httpGet(url, cookie);
        JSONObject root = new JSONObject(json);
        int errno = root.optInt("errno", -1);
        JSONArray data = root.optJSONArray("data");
        if (errno != 0 && data == null) {
            throw new IllegalStateException("115 列目录失败 errno=" + errno
                    + " " + root.optString("error"));
        }
        List<DirEntry> out = new ArrayList<>();
        for (int i = 0; data != null && i < data.length(); i++) {
            JSONObject it = data.getJSONObject(i);
            String name = first(it, "name", "n", "fn");
            if (name.isEmpty()) continue;
            String fid = first(it, "file_id", "fid");
            String childCid = first(it, "cid");
            String pc = first(it, "pick_code", "pc", "pickcode");
            boolean dir;
            if (it.has("is_dir")) {
                dir = it.optInt("is_dir", 0) == 1;
            } else {
                // Short schema: ico == "0" means a directory.
                String ico = first(it, "ico", "fc");
                dir = "0".equals(ico);
                if (fid.isEmpty() && !childCid.isEmpty()) dir = true;
            }
            long size = it.has("size") ? it.optLong("size") : it.optLong("s");
            long mtime = it.has("uptime") ? it.optLong("uptime")
                    : (it.has("ptime") ? it.optLong("ptime") : it.optLong("t"));
            DirEntry entry = new DirEntry(name, fid, childCid, pc, dir, size, mtime);
            out.add(entry);
            if (i < 3) {
                Log.i(TAG, "listDir sample: n=" + name + " fid=" + fid
                        + " cid=" + childCid + " isDir=" + dir
                        + " pc=" + pc + " s=" + size + " mtime=" + mtime);
            }
        }
        Log.i(TAG, "listDir " + cid + " -> " + out.size() + " entries");
        return out;
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    /** Browser-like UA. 115's web API + CDN reject generic/truncated UAs. */
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String REFERER = "https://115.com/";

    /** A resolved CDN link plus the one-time cookie the downurl API sets.
     *  115's CDN rejects the download with 403 "no cookie value" unless this
     *  exact cookie (from the /files/download response Set-Cookie) is sent. */
    public static final class ResolvedLink {
        public final String url;
        public final String cdnCookie;
        ResolvedLink(String url, String cdnCookie) {
            this.url = url; this.cdnCookie = cdnCookie;
        }
    }

    /** Direct links are stable for ~25 min. Cache them per (cookie,id) so a
     *  re-opened chapter / prefetch never re-hits the webapi resolve endpoints.
     *  115 rate-limits aggressive callers, which looked like "exit then the
     *  whole account disconnects". */
    private static final java.util.Map<String, ResolvedLink> DIRECT_CACHE =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, ResolvedLink>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, ResolvedLink> e) {
                    return size() > 256;
                }
            });

    /** 115Browser UA required by the app downurl endpoint. The legacy plain
     *  browser UA is throttled to zero since 2026; OpenList/115driver uses this
     *  exact string. */
    private static final String UA_115 = "Mozilla/5.0 115Browser/27.0.5.7";

    /** Resolves a downloadable direct URL for a file id (pickcode or fid).
     *  Uses the web /files/download endpoint (verified working with a valid
     *  cookie) and CAPTURES the Set-Cookie it returns - the CDN requires that
     *  cookie on every download or it answers 403 "no cookie value". */
    public static ResolvedLink resolveLink(String cookie, String id) {
        if (id == null || id.isEmpty()) return null;
        final String cacheKey = String.valueOf(cookie == null ? "" : cookie.hashCode())
                + "|" + id;
        ResolvedLink cached = DIRECT_CACHE.get(cacheKey);
        if (cached != null) return cached;

        String lastErr = null;
        final String[] templates = {"pickcode", "fid"};
        for (String t : templates) {
            String metaUrl = "https://webapi.115.com/files/download?aid=1&" + t + "=" + enc(id);
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(metaUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", UA_115);
                conn.setRequestProperty("Referer", REFERER);
                if (cookie != null && !cookie.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookie);
                }
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    lastErr = "HTTP " + code + " " + hostOf(metaUrl);
                    continue;
                }
                String meta = readAll(conn.getInputStream());
                // Collect the per-file cookie(s) the API sets for the CDN.
                StringBuilder cdn = new StringBuilder();
                java.util.List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
                if (setCookies != null) {
                    for (String sc : setCookies) {
                        String pair = sc.split(";", 2)[0].trim();
                        if (pair.isEmpty()) continue;
                        if (cdn.length() > 0) cdn.append("; ");
                        cdn.append(pair);
                    }
                }
                JSONObject root = new JSONObject(meta);
                String durl = root.optString("file_url", "");
                if (durl.isEmpty() && root.has("data")) {
                    JSONObject d = root.optJSONObject("data");
                    if (d != null) durl = d.optString("file_url", "");
                }
                if (!durl.isEmpty()) {
                    ResolvedLink link = new ResolvedLink(durl, cdn.toString());
                    DIRECT_CACHE.put(cacheKey, link);
                    Log.i(TAG, "resolveLink OK via " + t + " id=" + id
                            + " cdnCookie=" + (cdn.length() > 0 ? "yes" : "NO"));
                    return link;
                }
                lastErr = "errno=" + root.optInt("errno", -1) + " " + root.optString("error");
            } catch (Exception e) {
                lastErr = t + ": " + e.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        Log.e(TAG, "resolveLink FAILED id=" + id + " : " + lastErr);
        return null;
    }

    /** Backwards-compatible: just the URL string (no cookie). Only used by
     *  callers that manage the CDN header themselves. */
    public static String resolveDirectUrl(String cookie, String id) {
        ResolvedLink link = resolveLink(cookie, id);
        return link == null ? null : link.url;
    }

    /** POST https://proapi.115.com/app/chrome/downurl?t=<seconds> with form
     *  data=<m115 encrypted {"pickcode":id}>. Response data is another m115
     *  blob that decrypts to {pickcode:{url:{url:"..."}}}. Mirrors the Go
     *  driver's DownloadWithUA exactly. */
    private static String resolveViaAppApi(String cookie, String pickcode) throws Exception {
        M115Crypto.Key key = M115Crypto.generateKey();
        JSONObject req = new JSONObject();
        req.put("pickcode", pickcode);
        String payload = M115Crypto.encode(req.toString().getBytes(StandardCharsets.UTF_8), key);
        String url = "https://proapi.115.com/app/chrome/downurl?t="
                + (System.currentTimeMillis() / 1000L);
        String form = "data=" + enc(payload);

        // 115's resty client sends urlencoded body with a forced JSON content
        // type; try both because endpoints have flipped behavior across revs.
        String[] contentTypes = {"application/x-www-form-urlencoded", "application/json"};
        String lastErr = null;
        for (String ct : contentTypes) {
            try {
                String resp = httpPostWithUA(url, form, cookie, UA_115, ct);
                JSONObject root = new JSONObject(resp);
                int errno = root.optInt("errno", -1);
                String encData = root.optString("data", "");
                if (errno == 0 && !encData.isEmpty()) {
                    byte[] plain = M115Crypto.decode(encData, key);
                    JSONObject data = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                    java.util.Iterator<String> keys = data.keys();
                    while (keys.hasNext()) {
                        JSONObject info = data.optJSONObject(keys.next());
                        if (info == null) continue;
                        JSONObject urlObj = info.optJSONObject("url");
                        if (urlObj == null) continue;
                        String durl = urlObj.optString("url", "");
                        if (!durl.isEmpty()) return durl;
                    }
                }
                lastErr = "errno=" + errno + " " + root.optString("error");
            } catch (Exception e) {
                lastErr = e.getMessage();
            }
        }
        throw new IllegalStateException(lastErr == null ? "no data" : lastErr);
    }

    public static void clearDirectUrlCache() {
        DIRECT_CACHE.clear();
    }

    /** Resolves an image/video direct link via the web endpoint and downloads
     *  its bytes. [id] is normally a pickcode; when the listing did not provide a
     *  pickcode we fall back to the file_id. Both parameter forms are tried so
     *  a folder of loose images works regardless of which schema 115 returned.
     *  Large files (whole zip/cbz) are NOT supported here (errno 50028). */
    public static byte[] imageBytes(String cookie, String id) throws Exception {
        ResolvedLink link = resolveLink(cookie, id);
        if (link == null) {
            throw new IllegalStateException("115 取直链失败 id=" + id
                    + "（检查登录态 / 文件是否为图片或视频）");
        }
        // The CDN link is bound to the Set-Cookie from the downurl response,
        // NOT the login cookie. Send the CDN cookie (fall back to login cookie
        // for old cached links captured before this fix).
        String cdnCookie = !link.cdnCookie.isEmpty() ? link.cdnCookie : cookie;
        return httpGetBytes(link.url, cdnCookie, true);
    }

    /** Downloads raw bytes from a direct link. 115 CDN returns 403 without a
     *  browser UA + Referer. Follows up to 5 redirects (cross-host included,
     *  because 115 CDN often 302s to another host). */
    private static byte[] httpGetBytes(String url, String cookie, boolean follow) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        // CDN links are bound to the UA used at resolve time (115Browser).
        conn.setRequestProperty("User-Agent", UA_115);
        conn.setRequestProperty("Referer", REFERER);
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }
        try {
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400 && follow) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc != null && !loc.isEmpty()) {
                    String next = new URL(new URL(url), loc).toString();
                    return httpGetBytes(next, cookie, true);
                }
            }
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " 下载失败 " + hostOf(url));
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    // -------------------------------------------------------------- HTTP

    private static String httpGet(String url, String cookie) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", REFERER);
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
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", REFERER);
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

    /** POST with an explicit UA + content type (for the m115 downurl API). */
    private static String httpPostWithUA(String url, String form, String cookie,
                                         String ua, String contentType) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", contentType);
        conn.setRequestProperty("User-Agent", ua);
        conn.setRequestProperty("Referer", REFERER);
        conn.setRequestProperty("Accept", "application/json;charset=UTF-8");
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
                throw new IllegalStateException("HTTP " + code + " " + hostOf(url));
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
