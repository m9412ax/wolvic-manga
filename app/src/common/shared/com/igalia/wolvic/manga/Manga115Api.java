/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.manga;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
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

    /**
     * Moves files/folders to the recycle bin (115 deletes folders recursively
     * on the server). [parentCid] is the folder they live in; [ids] are the
     * file_id / cid values returned by {@link #listDir(String, String)}.
     */
    /** Creates a folder if it does not exist yet; returns its cid. */
    public static String ensureFolder(String cookie, String parentCid, String name)
            throws Exception {
        String pid = parentCid == null || parentCid.isEmpty() ? "0" : parentCid;
        for (DirEntry entry : listDir(cookie, pid)) {
            if (entry.isDir && name.equals(entry.name)) {
                return entry.cid;
            }
        }
        String json = httpPost("https://webapi.115.com/files/add",
                "pid=" + enc(pid) + "&name=" + enc(name) + "&type=1", cookie);
        JSONObject root = new JSONObject(json);
        String cid = first(root, "cid", "fid", "id");
        if (cid.isEmpty() && root.optJSONObject("data") != null) {
            cid = first(root.optJSONObject("data"), "cid", "fid", "id");
        }
        if (cid.isEmpty()) {
            throw new IllegalStateException("115 建目录失败 " + root.optString("error", json));
        }
        return cid;
    }

    /** Finds a file by name in a 115 folder (used by the sync manifest). */
    public static String findFile(String cookie, String cid, String name) throws Exception {
        for (DirEntry entry : listDir(cookie, cid)) {
            if (!entry.isDir && name.equals(entry.name)) {
                return entry.fileId.isEmpty() ? entry.cid : entry.fileId;
            }
        }
        return null;
    }

    /** Reads a small remote file via the direct-link API (works for .json too). */
    public static byte[] fileBytes(String cookie, String fileId) throws Exception {
        String url = resolveDirectUrl(cookie, fileId);
        if (url == null || url.isEmpty()) {
            throw new IllegalStateException("115 取直链失败 id=" + fileId);
        }
        return httpGetBytes(url, cookie, true);
    }

    /**
     * Uploads a small text file into a 115 folder via the WebAPI multipart
     * endpoint. Some accounts gate it behind the newer upload gateway; in that
     * case an error is thrown and the caller can fall back to the SMB backend.
     */
    public static void uploadText(String cookie, String cid, String name,
                                  String content) throws Exception {
        String boundary = "----MangaHubSync" + System.currentTimeMillis();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"pid\"\r\n\r\n"
                + (cid == null || cid.isEmpty() ? "0" : cid) + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n";
        body.write(head.getBytes(StandardCharsets.UTF_8));
        body.write(content.getBytes(StandardCharsets.UTF_8));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://webapi.115.com/files/upload").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", REFERER);
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }
        try {
            byte[] bytes = body.toByteArray();
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            int code = conn.getResponseCode();
            String response = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            JSONObject result = new JSONObject(response);
            boolean ok = result.optBoolean("state", false) || result.optInt("errno", -1) == 0;
            if (!ok) {
                throw new IllegalStateException("115 上传失败 " + result.optString("error", response));
            }
            Log.i(TAG, "uploadText " + name + " -> " + cid);
        } finally {
            conn.disconnect();
        }
    }

    public static void deleteFiles(String cookie, String parentCid,
                                   java.util.List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        if (parentCid == null || parentCid.isEmpty()) {
            parentCid = "0";
        }
        StringBuilder form = new StringBuilder();
        form.append("pid=").append(enc(parentCid));
        for (int i = 0; i < ids.size(); i++) {
            form.append("&fid[").append(i).append("]=").append(enc(ids.get(i)));
        }
        String json = httpPost("https://webapi.115.com/rb/delete", form.toString(), cookie);
        JSONObject root = new JSONObject(json);
        boolean ok = root.optBoolean("state", false) || root.optInt("errno", -1) == 0;
        if (!ok) {
            throw new IllegalStateException("115 删除失败 " + root.optString("error"));
        }
        Log.i(TAG, "deleteFiles " + parentCid + " -> " + ids.size());
    }

    private static String first(JSONObject o, String... keys) {        for (String k : keys) {
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

    /** Header pairs for embedding a resolved 115 CDN link in another HTTP
     *  client (LibVLC). The CDN rejects Range requests without the one-time
     *  cookie captured by resolveLink(). */
    public static String[] videoHeaders(ResolvedLink link) {
        if (link == null) return null;
        return new String[] {
                "Cookie", link.cdnCookie == null ? "" : link.cdnCookie,
                "Referer", REFERER,
                "User-Agent", UA_115
        };
    }

    /** Resolves a downloadable direct URL for a file id (pickcode or fid).
     *  Uses the web /files/download endpoint (verified working with a valid
     *  cookie) and CAPTURES the Set-Cookie it returns - the CDN requires that
     *  cookie on every download or it answers 403 "no cookie value". */
    /** Short-lived negative cache: a file whose links cannot be resolved is
     *  retried at most once per 15 s. Without this, one failing image re-runs
     *  the whole 3-endpoint chain (up to 45 s) on EVERY page request, which
     *  freezes a sequentially-downloaded chapter. */
    private static final java.util.Map<String, Long> FAIL_CACHE = new java.util.HashMap<>();

    public static ResolvedLink resolveLink(String cookie, String id) {
        if (id == null || id.isEmpty()) return null;
        final String cacheKey = String.valueOf(cookie == null ? "" : cookie.hashCode())
                + "|" + id;
        ResolvedLink cached = DIRECT_CACHE.get(cacheKey);
        if (cached != null) return cached;
        synchronized (FAIL_CACHE) {
            Long until = FAIL_CACHE.get(cacheKey);
            if (until != null && until > System.currentTimeMillis()) return null;
        }

        String lastErr = null;
        // --- Primary: encrypted 115 app API. This is the ONLY endpoint that
        // resolves large files (>2 GB); the web /files/download returns 50028
        // for them. It also works for small files, so try it first for all.
        try {
            ResolvedLink appLink = resolveViaAppApi(cookie, id);
            if (appLink != null && !appLink.url.isEmpty()) {
                DIRECT_CACHE.put(cacheKey, appLink);
                synchronized (FAIL_CACHE) { FAIL_CACHE.remove(cacheKey); }
                Log.i(TAG, "resolveLink OK via app API id=" + id);
                return appLink;
            }
        } catch (Exception e) {
            lastErr = "app API: " + e.getMessage();
            Log.w(TAG, "resolveViaAppApi failed id=" + id + " : " + e.getMessage());
        }

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
                    synchronized (FAIL_CACHE) { FAIL_CACHE.remove(cacheKey); }
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
        synchronized (FAIL_CACHE) { FAIL_CACHE.put(cacheKey, System.currentTimeMillis() + 15000L); }
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
    private static ResolvedLink resolveViaAppApi(String cookie, String pickcode) throws Exception {
        M115Crypto.Key key = M115Crypto.generateKey();
        JSONObject req = new JSONObject();
        req.put("pickcode", pickcode);
        String payload = M115Crypto.encode(req.toString().getBytes(StandardCharsets.UTF_8), key);
        String url = "https://proapi.115.com/app/chrome/downurl?t="
                + (System.currentTimeMillis() / 1000L);
        String form = "data=" + enc(payload);

        String resp = httpPostWithUA(url, form, cookie, UA_115,
                "application/x-www-form-urlencoded");
        JSONObject root = new JSONObject(resp);
        // Success is {"state":true, "msg_code":0, "data":"<m115 blob>"}.
        // Do NOT look at "errno": it is absent on success (optInt -> -1).
        boolean ok = root.optBoolean("state", false) || root.optInt("msg_code", -1) == 0
                || root.optInt("errno", -1) == 0;
        String encData = root.optString("data", "");
        if (!ok || encData.isEmpty()) {
            throw new IllegalStateException("app downurl state=" + root.optBoolean("state", false)
                    + " errno=" + root.optInt("errno", -1)
                    + " msg=" + root.optString("msg", root.optString("error")));
        }
        byte[] plain = M115Crypto.decode(encData, key);
        JSONObject data = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        // Response shape: { "<pickcode>": { "url": { "url": "https://..." },
        //   "file_size":..., "header": {...} } } or a flat {"url":{"url":...}}.
        java.util.Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            JSONObject info = data.optJSONObject(keys.next());
            if (info == null) continue;
            JSONObject urlObj = info.optJSONObject("url");
            if (urlObj == null) continue;
            String durl = urlObj.optString("url", "");
            if (durl.isEmpty()) continue;
            // The app API embeds the CDN auth cookie in the decrypted payload:
            // url.auth_cookie = {name, value, path}. The CDN 403s with
            // "no cookie value" unless we send name=value on the download.
            JSONObject auth = urlObj.optJSONObject("auth_cookie");
            String cdnCookie = "";
            if (auth != null) {
                String name = auth.optString("name", "");
                String value = auth.optString("value", "");
                if (!name.isEmpty() && !value.isEmpty()) {
                    cdnCookie = name + "=" + value;
                }
            }
            if (cdnCookie.isEmpty() && cookie != null) cdnCookie = cookie;
            Log.i(TAG, "app downurl resolved size=" + info.optString("file_size", "?")
                    + " authCookie=" + (cdnCookie.isEmpty() ? "NO" : "yes"));
            return new ResolvedLink(durl, cdnCookie);
        }
        throw new IllegalStateException("app downurl response has no url");
    }

    public static void clearDirectUrlCache() {
        DIRECT_CACHE.clear();
        synchronized (FAIL_CACHE) { FAIL_CACHE.clear(); }
    }

    /** Resolves an image/video direct link via the web endpoint and downloads
     *  its bytes. [id] is normally a pickcode; when the listing did not provide a
     *  pickcode we fall back to the file_id. Both parameter forms are tried so
     *  a folder of loose images works regardless of which schema 115 returned.
     *  Large files (whole zip/cbz) are NOT supported here (errno 50028). */
    /** Streams one file to dest with byte progress; avoids holding a 40 MB
     *  image fully in memory and lets the reader show a real per-file bar. */
    public static void downloadToFile(String cookie, String id, File dest,
                                      RemoteManga.ProgressListener listener) throws Exception {
        ResolvedLink link = resolveLink(cookie, id);
        if (link == null) {
            throw new IllegalStateException("115 取直链失败 id=" + id);
        }
        String cdnCookie = !link.cdnCookie.isEmpty() ? link.cdnCookie : cookie;
        HttpURLConnection conn = (HttpURLConnection) new URL(link.url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", UA_115);
        conn.setRequestProperty("Referer", REFERER);
        if (cdnCookie != null && !cdnCookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cdnCookie);
        }
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        try {
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc != null && !loc.isEmpty()) {
                    downloadDirectUrl(new URL(new URL(link.url), loc).toString(),
                            cdnCookie, tmp, listener);
                    if (dest.exists()) dest.delete();
                    if (!tmp.renameTo(dest)) throw new IllegalStateException("重命名失败 " + id);
                    return;
                }
            }
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " 下载失败 " + hostOf(link.url));
            }
            long total = conn.getContentLengthLong();
            long[] done = {0L};
            try (InputStream in = conn.getInputStream();
                 java.io.OutputStream out = new java.io.BufferedOutputStream(
                         new java.io.FileOutputStream(tmp), 16384)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done[0] += n;
                    if (listener != null) listener.onProgress(done[0], total);
                }
            }
            if (tmp.length() == 0L) throw new IllegalStateException("115 下载为空 " + id);
            if (dest.exists() && !dest.delete()) { /* best effort */ }
            if (!tmp.renameTo(dest)) throw new IllegalStateException("重命名失败 " + id);
        } finally {
            conn.disconnect();
        }
    }

    /** Internal redirect helper: download a fully-resolved URL with an explicit
     *  cookie (no further resolve). */
    private static void downloadDirectUrl(String directUrl, String cdnCookie, File dest,
                                          RemoteManga.ProgressListener listener) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(directUrl).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", UA_115);
        conn.setRequestProperty("Referer", REFERER);
        if (cdnCookie != null && !cdnCookie.isEmpty()) conn.setRequestProperty("Cookie", cdnCookie);
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream in = conn.getInputStream();
             java.io.OutputStream out = new java.io.BufferedOutputStream(
                     new java.io.FileOutputStream(dest), 16384)) {
            byte[] buf = new byte[16384];
            int n;
            long done = 0L, total = conn.getContentLengthLong();
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (listener != null) listener.onProgress(done, total);
            }
        } finally {
            conn.disconnect();
        }
    }

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
