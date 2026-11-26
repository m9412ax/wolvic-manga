package com.igalia.wolvic.manga;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sidecar for a VIDEO page served by a Tachiyomi extension source.
 *
 * <p>Extension chapters can mix images and videos (cosplay sites routinely post
 * one clip plus a photo set). Downloading a clip the way a page is downloaded
 * does not work: the file is tens of megabytes, the CDN throttles it, and the
 * reader waits for page 0 to land before it shows anything - so the whole
 * chapter stayed on "正在缓存" forever.
 *
 * <p>Instead the page is materialised as a tiny marker file named after the
 * video (e.g. {@code 0000.mp4}) holding the stream URL and the request headers
 * the extension would send. The reader sees a normal video page, recognises the
 * marker, and hands the URL to ExoPlayer so the clip streams with seek support
 * - exactly the way SMB / 115 videos already work.
 */
public final class SourceVideo {

    private static final String MAGIC = "WOLVIC-SRC-VIDEO-1";
    private static final String HDR = "H:";

    private SourceVideo() { }

    /** Resolved stream address plus the headers needed to fetch it. */
    public static final class Stream {
        public final String url;
        public final Map<String, String> headers;

        Stream(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    /** True when [url] points at a playable media stream rather than an image. */
    public static boolean isVideoUrl(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase(Locale.ROOT);
        int cut = u.indexOf('?');
        if (cut >= 0) {
            u = u.substring(0, cut);
        }
        cut = u.indexOf('#');
        if (cut >= 0) {
            u = u.substring(0, cut);
        }
        return u.endsWith(".mp4") || u.endsWith(".m4v") || u.endsWith(".webm")
                || u.endsWith(".mkv") || u.endsWith(".mov") || u.endsWith(".m3u8")
                || u.endsWith(".mpd") || u.endsWith(".flv");
    }

    /** File extension for a marker so the reader's video checks keep working. */
    public static String extensionFor(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        int cut = u.indexOf('?');
        if (cut >= 0) {
            u = u.substring(0, cut);
        }
        if (u.endsWith(".webm")) return "webm";
        if (u.endsWith(".mkv")) return "mkv";
        if (u.endsWith(".mov")) return "mov";
        if (u.endsWith(".m4v")) return "m4v";
        if (u.endsWith(".flv")) return "flv";
        return "mp4";
    }

    public static void write(File dest, String url, Map<String, String> headers) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(MAGIC).append('\n');
        sb.append(oneLine(url)).append('\n');
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                sb.append(HDR).append(oneLine(e.getKey())).append('\t')
                        .append(oneLine(e.getValue())).append('\n');
            }
        }
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(sb.toString().getBytes("UTF-8"));
        }
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("无法写入视频标记：" + dest.getName());
        }
    }

    public static boolean isMarker(File file) {
        if (file == null || !file.isFile() || file.length() > 64 * 1024) {
            return false;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            return MAGIC.equals(r.readLine());
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Reads a marker written by {@link #write}; null when [file] is not one. */
    public static Stream read(File file) {
        if (!isMarker(file)) {
            return null;
        }
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            r.readLine();                        // magic
            String url = r.readLine();
            if (url == null || url.isEmpty()) {
                return null;
            }
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith(HDR)) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= HDR.length()) {
                    continue;
                }
                headers.put(line.substring(HDR.length(), tab), line.substring(tab + 1));
            }
            return new Stream(url, headers);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }
}
