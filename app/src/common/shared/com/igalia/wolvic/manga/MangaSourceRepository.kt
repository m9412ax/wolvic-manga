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
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Browse / details / chapter-download pipeline for loaded extension sources.
 * Results are delivered on the main thread; all network work runs on IO.
 */
@OptIn(DelicateCoroutinesApi::class)
object MangaSourceRepository {

    private const val TAG = "MangaSource"
    private val main = Handler(Looper.getMainLooper())

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

    /** Number of leading pages downloaded before the reader is allowed to open
     *  a chapter. Small enough for a fast first page, large enough that the
     *  user never hits the end of the pre-downloaded window immediately. */
    private const val OPEN_AFTER_PAGES = 8

    /** Downloads a chapter into a stable cache folder and returns that folder
     *  so the existing file-based reader can open it. The first [OPEN_AFTER_PAGES]
     *  pages are fetched first and the folder is handed back immediately so the
     *  first picture appears fast; the remaining pages keep downloading in the
     *  background (idempotent - already cached pages are skipped). This also
     *  fixes the "only 4 images after switching chapter" bug: pre-fetch used to
     *  leave 4 pages on disk and the reader mistook a non-empty folder for a
     *  complete chapter, so it never downloaded the rest. */
    fun downloadChapter(
        context: Context,
        source: Source,
        manga: SManga,
        chapter: SChapter,
        progress: Progress,
        cb: Callback<File>,
    ) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val pages = source.getPageList(chapter)
                if (pages.isEmpty()) throw IllegalStateException("章节页面列表为空")
                val dir = chapterDir(context, source, manga, chapter)
                dir.mkdirs()
                Log.i(TAG, "download ${source.name} '${chapter.name}' pages=${pages.size} -> $dir")
                var opened = false
                // First pass: leading window, then report the folder so the
                // reader can show page 1 while the tail keeps downloading.
                val head = pages.take(OPEN_AFTER_PAGES)
                val tail = pages.drop(OPEN_AFTER_PAGES)
                for ((i, page) in head.withIndex()) {
                    if (existingPage(dir, page.index) == null) fetchPage(source, page, dir)
                    main.post { progress.onProgress(i + 1, pages.size) }
                }
                opened = true
                main.post { cb.onResult(dir, null) }
                // Second pass: the rest of the chapter. Failures here are
                // non-fatal - the reader is already open on the head pages.
                tail.forEachIndexed { k, page ->
                    try {
                        if (existingPage(dir, page.index) == null) {
                            fetchPage(source, page, dir)
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "page ${page.index} failed: ${e.message}")
                    }
                    main.post { progress.onProgress(OPEN_AFTER_PAGES + k + 1, pages.size) }
                }
                Log.i(TAG, "download done: $dir (opened=$opened)")
                enforceCacheCap(context)
            } catch (e: Throwable) {
                Log.e(TAG, "download failed: ${source.name} '${chapter.name}'", e)
                if (!opened) main.post { cb.onResult(null, e.message ?: e.javaClass.simpleName) }
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
                val pages = source.getPageList(chapter)
                dir.mkdirs()
                var done = 0
                for (page in pages) {
                    if (done >= maxPages) break
                    if (existingPage(dir, page.index) == null) {
                        fetchPage(source, page, dir)
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

    /** Evicts oldest chapter cache folders once the configured cap is exceeded. */
    private fun enforceCacheCap(context: Context) {
        try {
            val base = File(context.externalCacheDir ?: context.cacheDir, "manga-sources")
            val capMb = MangaSettings.get(context).integer(MangaSettings.SOURCE_CACHE_MB)
            val cap = capMb.toLong() * 1024 * 1024
            val chapters = mutableListOf<File>()
            base.listFiles()?.forEach { src ->
                src.listFiles()?.forEach { manga ->
                    manga.listFiles()?.forEach { ch -> if (ch.isDirectory) chapters.add(ch) }
                }
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