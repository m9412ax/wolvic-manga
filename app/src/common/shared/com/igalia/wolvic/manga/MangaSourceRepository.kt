package com.igalia.wolvic.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.Hash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Browse / details / chapter-download pipeline for loaded extension sources.
 * Results are delivered on the main thread; all network work runs on IO.
 */
@OptIn(DelicateCoroutinesApi::class)
object MangaSourceRepository {

    private const val TAG = "MangaSource"
    private val main = Handler(Looper.getMainLooper())

    /** A source's page list is the SAME network request (usually the whole
     *  detail HTML) for details, prefetch and download. Cache it briefly so
     *  switching into a chapter does not re-fetch the page three times. Value
     *  is (timestamp, pages); entries older than TTL are re-fetched. */
    private data class PageListEntry(val at: Long, val pages: List<eu.kanade.tachiyomi.source.model.Page>)
    private val pageListCache = java.util.concurrent.ConcurrentHashMap<String, PageListEntry>()
    private const val PAGE_LIST_TTL_MS = 5 * 60 * 1000L

    private fun pageListKey(source: Source, chapter: SChapter): String =
        source.id.toString() + ":" + chapter.url

    /** Returns the page list from a short-lived cache, fetching once on miss.
     *  Concurrent callers may still race, but subsequent switches are instant. */
    private suspend fun cachedPageList(source: Source, chapter: SChapter): List<eu.kanade.tachiyomi.source.model.Page> {
        val key = pageListKey(source, chapter)
        val cached = pageListCache[key]
        if (cached != null && System.currentTimeMillis() - cached.at < PAGE_LIST_TTL_MS) {
            return cached.pages
        }
        val pages = source.getPageList(chapter)
        pageListCache[key] = PageListEntry(System.currentTimeMillis(), pages)
        return pages
    }

    fun interface Callback<T> {
        fun onResult(value: T?, error: String?)
    }

    fun interface Progress {
        fun onProgress(done: Int, total: Int)
    }

    fun browse(source: Source, page: Int, query: String, filters: FilterList?, cb: Callback<MangasPage>) {
        if (source !is CatalogueSource) {
            cb.onResult(null, "该源不支持浏览")
            return
        }
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val result = if (query.isBlank() && filters == null) {
                    source.getPopularManga(page)
                } else {
                    source.getSearchManga(page, query, filters ?: source.getFilterList())
                }
                Log.i(TAG, "browse ${source.name} page=$page q='$query' filters=${filters != null} -> ${result.mangas.size} hasNext=${result.hasNextPage}")
                main.post { cb.onResult(result, null) }
            } catch (e: Throwable) {
                Log.e(TAG, "browse failed: ${source.name}", e)
                main.post { cb.onResult(null, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun fetchFilters(source: Source, cb: Callback<FilterList>) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val filters = source.getFilterList()
                main.post { cb.onResult(filters, null) }
            } catch (e: Throwable) {
                Log.e(TAG, "getFilterList failed: ${source.name}", e)
                main.post { cb.onResult(null, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Fetches a cover thumbnail with the source's own client/headers and
     *  decodes it downsampled; null on any failure (caller shows placeholder). */
    fun loadCover(source: Source, url: String?, cb: Callback<Bitmap>) {
        if (url.isNullOrBlank()) {
            cb.onResult(null, null)
            return
        }
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val request = if (source is HttpSource) GET(url, source.headers) else GET(url)
                val client = if (source is HttpSource) source.client else plainClient
                val bytes = client.newCall(request).awaitSuccess().use { it.body.bytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    while (bounds.outWidth / (sample * 2) >= COVER_PX && bounds.outHeight / (sample * 2) >= COVER_PX) {
                        sample *= 2
                    }
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                main.post { cb.onResult(bitmap, null) }
            } catch (e: Throwable) {
                Log.w(TAG, "cover load failed: $url (${e.message})")
                main.post { cb.onResult(null, e.message) }
            }
        }
    }

    private const val COVER_PX = 320

    private val plainClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun latest(source: Source, page: Int, cb: Callback<MangasPage>) {
        if (source !is CatalogueSource) {
            cb.onResult(null, "该源不支持浏览")
            return
        }
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val result = source.getLatestUpdates(page)
                main.post { cb.onResult(result, null) }
            } catch (e: Throwable) {
                Log.e(TAG, "latest failed: ${source.name}", e)
                main.post { cb.onResult(null, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun details(source: Source, manga: SManga, cb: Callback<SMangaUpdate>) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val update = source.getMangaUpdate(manga, emptyList(), true, true)
                Log.i(TAG, "details ${source.name}: ${update.manga.title} chapters=${update.chapters.size}")
                main.post { cb.onResult(update, null) }
            } catch (e: Throwable) {
                Log.e(TAG, "details failed: ${source.name}", e)
                main.post { cb.onResult(null, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Max simultaneous page downloads. okhttp per-host limit is raised to 8
     *  in NetworkHelper so concurrency here is actually realised on the wire. */
    private const val DOWNLOAD_CONCURRENCY = 6
    private const val PRIORITY_HEAD = 4

    /** Downloads a chapter into a stable cache folder and returns that folder.
     *  Layered priority, all concurrent - no page waits for a whole batch:
     *   1. page 0 first -> folder handed back immediately (fast first picture);
     *   2. the leading [PRIORITY_HEAD] pages;
     *   3. the 4 pages AFTER [startPage] (reader's current position) jump the
     *      queue so continuous reading never stalls;
     *   4. every remaining page downloads concurrently, semaphore-capped.
     *  Each page is handled once; progress always reports the REAL total. */
    fun downloadChapter(
        context: Context,
        source: Source,
        manga: SManga,
        chapter: SChapter,
        progress: Progress,
        cb: Callback<File>,
        startPage: Int = 0,
    ) {
        GlobalScope.launch(Dispatchers.IO) {
            var opened = false
            try {
                val pages = cachedPageList(source, chapter)
                if (pages.isEmpty()) throw IllegalStateException("章节页面列表为空")
                val dir = chapterDir(context, source, manga, chapter)
                dir.mkdirs()
                val total = pages.size
                val start = startPage.coerceIn(0, total - 1)
                Log.i(TAG, "download ${source.name} '${chapter.name}' pages=$total start=$start -> $dir")

                val reported = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
                val done = AtomicInteger(0)
                val sem = Semaphore(DOWNLOAD_CONCURRENCY)

                suspend fun one(i: Int) {
                    if (i < 0 || i >= total || !reported.add(i)) return
                    try {
                        if (existingPage(dir, pages[i].index) == null) {
                            fetchPageWithRetry(source, pages[i], dir)
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "page ${i + 1}/$total gave up: ${e.message}"
                            + " url=${pages[i].imageUrl ?: pages[i].url}")
                    } finally {
                        val d = done.incrementAndGet()
                        main.post { progress.onProgress(d, total) }
                        if (d % 8 == 0) enforceCacheCap(context)
                    }
                }

                // Stage 1: first page alone, hand the folder straight back.
                one(0)
                if (!opened) {
                    opened = true
                    main.post { cb.onResult(dir, null) }
                }

                // Stage 2: leading window.
                coroutineScope {
                    (1 until minOf(PRIORITY_HEAD, total))
                        .map { i -> async(Dispatchers.IO) { one(i) } }
                        .awaitAll()
                }

                // Stage 3: pages just after the reader's current position.
                coroutineScope {
                    ((start + 1) until minOf(start + 5, total))
                        .map { i -> async(Dispatchers.IO) { one(i) } }
                        .awaitAll()
                }

                // Stage 4: everything left, globally concurrent but capped.
                coroutineScope {
                    (0 until total)
                        .map { i -> async(Dispatchers.IO) { sem.withPermit { one(i) } } }
                        .awaitAll()
                }

                Log.i(TAG, "download done: $dir pages=$total (opened=$opened)")
                enforceCacheCap(context)
            } catch (e: Throwable) {
                Log.e(TAG, "download failed: ${source.name} '${chapter.name}'", e)
                if (!opened) main.post { cb.onResult(null, e.message ?: e.javaClass.simpleName) }
                enforceCacheCap(context)
            }
        }
    }

    private val prefetching = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Disk-only pre-cache of the first [maxPages] pages of a chapter so the
     *  reader can switch chapters instantly. Silent: failures are logged. */
    fun prefetchChapter(context: Context, source: Source, manga: SManga, chapter: SChapter, maxPages: Int) {
        val key = source.id.toString() + ":" + chapter.url
        if (!prefetching.add(key)) return
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val dir = chapterDir(context, source, manga, chapter)
                val existing = dir.listFiles()?.count { !it.name.endsWith(".tmp") && it.length() > 0 } ?: 0
                if (existing >= maxPages) return@launch
                // Reuse the cached page list: the detail HTML is expensive to
                // fetch. This warm-up also primes the cache for the moment the
                // reader actually opens this chapter.
                val pages = cachedPageList(source, chapter)
                dir.mkdirs()
                var done = 0
                for (page in pages) {
                    if (done >= maxPages) break
                    if (existingPage(dir, page.index) == null) {
                        fetchPageWithRetry(source, page, dir)
                    }
                    done++
                }
                Log.i(TAG, "prefetch ${source.name} '${chapter.name}' $done pages -> $dir")
                enforceCacheCap(context)
            } catch (e: Throwable) {
                Log.w(TAG, "prefetch failed: ${source.name} '${chapter.name}' (${e.message})")
            } finally {
                prefetching.remove(key)
            }
        }
    }

    private suspend fun fetchPage(source: Source, page: eu.kanade.tachiyomi.source.model.Page, dir: File) {
        if (page.imageUrl == null && source is HttpSource) {
            page.imageUrl = source.getImageUrl(page)
        }
        val response = when {
            source is HttpSource && page.imageUrl != null -> source.getImage(page)
            page.imageUrl != null -> plainClient.newCall(GET(page.imageUrl!!)).awaitSuccess()
            else -> throw IllegalStateException("第 ${page.index + 1} 页没有图片地址")
        }
        response.use { resp ->
            val body = resp.body
            val ext = extensionFor(body.contentType()?.toString(), page.imageUrl ?: page.url)
            val out = File(dir, "%04d.%s".format(page.index, ext))
            val tmp = File(dir, out.name + ".tmp")
            body.byteStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmp.length() == 0L) throw IllegalStateException("第 ${page.index + 1} 页下载为空")
            tmp.renameTo(out)
        }
    }

    /**
     * Pages fail intermittently (CDN hiccups, rate limits, transient 5xx).
     * Without a retry the last page or two of a chapter simply never landed,
     * which the reader could not distinguish from "still downloading" - the
     * user saw the chapter end two pages early and could not turn further.
     */
    private suspend fun fetchPageWithRetry(
        source: Source,
        page: eu.kanade.tachiyomi.source.model.Page,
        dir: File,
        attempts: Int = 3,
    ) {
        var last: Throwable? = null
        for (n in 0 until attempts) {
            try {
                fetchPage(source, page, dir)
                return
            } catch (e: Throwable) {
                last = e
                if (n < attempts - 1) {
                    Log.w(TAG, "page ${page.index + 1} retry ${n + 1}: ${e.message}")
                    delay(400L * (n + 1))
                }
            }
        }
        throw last ?: IllegalStateException("第 ${page.index + 1} 页下载失败")
    }

    /**
     * Drops the cached page list and re-runs the download pass. Used when a
     * chapter LOOKS like it ends early: the first list can be a truncated
     * response (pre-login HTML, partial JSON) and a fresh request sometimes
     * returns the full chapter. Files already on disk are skipped.
     */
    fun refreshChapter(
        context: Context,
        source: Source,
        manga: SManga,
        chapter: SChapter,
        cb: Callback<File>,
    ) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                pageListCache.remove(pageListKey(source, chapter))
                val pages = cachedPageList(source, chapter)
                if (pages.isEmpty()) throw IllegalStateException("章节页面列表为空")
                val dir = chapterDir(context, source, manga, chapter)
                dir.mkdirs()
                Log.i(TAG, "refresh list: ${source.name} '${chapter.name}' pages=${pages.size}")
                // Land the FIRST missing page so the reader immediately sees a
                // longer chapter and can keep turning; the remaining pages come
                // on demand through fetchSinglePage.
                for (page in pages) {
                    if (existingPage(dir, page.index) == null) {
                        try {
                            fetchPageWithRetry(source, page, dir)
                        } catch (e: Throwable) {
                            Log.w(TAG, "refresh page ${page.index + 1} failed: ${e.message}")
                        }
                        break
                    }
                }
                main.post { cb.onResult(dir, null) }
            } catch (e: Throwable) {
                Log.w(TAG, "refresh list failed: ${e.message}")
                main.post { cb.onResult(null, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /**
     * Fetches ONE page on demand. The reader calls this when it lands on a page
     * that the bulk pass gave up on, instead of waiting forever for progress
     * that will never come.
     */
    fun fetchSinglePage(
        context: Context,
        source: Source,
        manga: SManga,
        chapter: SChapter,
        pageIndex: Int,
        cb: Callback<File>,
    ) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                var pages = cachedPageList(source, chapter)
                if (pageIndex >= pages.size) {
                    // The cached list may be a truncated response: drop it and
                    // ask the source once more before giving up.
                    pageListCache.remove(pageListKey(source, chapter))
                    pages = cachedPageList(source, chapter)
                }
                if (pageIndex < 0 || pageIndex >= pages.size) {
                    main.post { cb.onResult(null, "页码越界") }
                    return@launch
                }
                val dir = chapterDir(context, source, manga, chapter)
                dir.mkdirs()
                val page = pages[pageIndex]
                if (existingPage(dir, page.index) == null) {
                    fetchPageWithRetry(source, page, dir)
                }
                Log.i(TAG, "single page ${pageIndex + 1}/${pages.size} ok -> $dir")
                main.post { cb.onResult(dir, null) }
            } catch (e: Throwable) {
                Log.w(TAG, "single page ${pageIndex + 1} failed: ${e.message}")
                main.post { cb.onResult(null, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Evicts oldest chapter cache folders once the configured cap is exceeded. */
    private fun enforceCacheCap(context: Context) {
        try {
            val base = File(context.externalCacheDir ?: context.cacheDir, "manga-sources")
            val capMb = MangaSettings.get(context).integer(MangaSettings.IMAGE_CACHE_MB)
            val cap = capMb.toLong() * 1024 * 1024
            val chapters = mutableListOf<File>()
            base.listFiles()?.forEach { src ->
                src.listFiles()?.forEach { manga ->
                    manga.listFiles()?.forEach { ch -> if (ch.isDirectory) chapters.add(ch) }
                }
            }
            // Clear interrupted-download leftovers first; they count against
            // the cap but are never readable and would linger forever.
            for (ch in chapters) {
                ch.listFiles()?.forEach { f -> if (f.isFile && f.name.endsWith(".tmp")) f.delete() }
            }
            var total = chapters.sumOf { dirSize(it) }
            if (total <= cap) return
            chapters.sortBy { it.lastModified() }
            for (ch in chapters) {
                if (total <= cap) break
                val size = dirSize(ch)
                if (ch.deleteRecursively()) {
                    total -= size
                    Log.i(TAG, "cache evict $ch ($size bytes)")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "cache cap enforcement failed (${e.message})")
        }
    }

    private fun dirSize(dir: File): Long =
        dir.listFiles()?.sumOf { if (it.isDirectory) dirSize(it) else it.length() } ?: 0L

    fun chapterDir(context: Context, source: Source, manga: SManga, chapter: SChapter): File {
        val base = context.externalCacheDir ?: context.cacheDir
        return File(
            base,
            "manga-sources/" + source.id + "/" + Hash.md5(manga.url) + "/" + Hash.md5(chapter.url),
        )
    }

    private fun existingPage(dir: File, index: Int): File? {
        val prefix = "%04d.".format(index)
        return dir.listFiles()?.firstOrNull {
            it.name.startsWith(prefix) && !it.name.endsWith(".tmp") && it.length() > 0
        }
    }

    private fun extensionFor(contentType: String?, url: String): String {
        val ct = contentType?.lowercase().orEmpty()
        return when {
            ct.contains("jpeg") || ct.contains("jpg") -> "jpg"
            ct.contains("png") -> "png"
            ct.contains("webp") -> "webp"
            ct.contains("avif") -> "avif"
            ct.contains("gif") -> "gif"
            ct.contains("bmp") -> "bmp"
            ct.contains("mp4") -> "mp4"
            ct.contains("webm") -> "webm"
            ct.contains("mkv") -> "mkv"
            else -> {
                val path = url.substringBefore('?').substringAfterLast('/', "")
                val dot = path.lastIndexOf('.')
                if (dot in 1..path.length - 2) {
                    path.substring(dot + 1).lowercase().takeIf { it.length <= 5 } ?: "jpg"
                } else {
                    "jpg"
                }
            }
        }
    }
}