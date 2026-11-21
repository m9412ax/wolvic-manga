package eu.kanade.tachiyomi.extension.zh.cosjun

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Cosjun (cosjun.cn) — WordPress/ripro image gallery source.
 *
 * Navigation mirrors the site's five top-level tabs:
 *   COS妹子 /photo · COS本子 /benzi · 少女日常 /life · 付费专区 /zhuanqu · 妹子目录 /tags
 * "全部" (the first catalogue option) aggregates the four content tabs and
 * merges them newest-first. "妹子目录" is the COS-name tag directory: its tags
 * are exposed as flat, tappable chips in the filter panel.
 */
class Cosjun : HttpSource(), ConfigurableSource {

    override val name = "COS君"
    override val baseUrl = "https://www.cosjun.cn"
    override val lang = "zh"
    override val supportsLatest = true
    override val versionId = 2

    private val prefs: SharedPreferences by lazy {
        Injekt.get<android.content.Context>()
            .getSharedPreferences("source_$id", android.content.Context.MODE_PRIVATE)
    }

    // ------------------------------------------------------------- preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = KEY_USER
            title = "COS君账号（用户名或邮箱）"
            summary = prefs.getString(KEY_USER, "") ?: ""
            setOnPreferenceChangeListener { _, v ->
                summary = v.toString()
                loginStamp = 0L
                prefs.edit().remove(KEY_SESSION).apply()
                true
            }
        })
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = KEY_PASS
            title = "COS君密码"
            summary = "仅本机保存，用于登录解锁全量图片与视频"
            setOnPreferenceChangeListener { _, _ ->
                loginStamp = 0L
                prefs.edit().remove(KEY_SESSION).apply()
                true
            }
        })
        screen.addPreference(androidx.preference.Preference(screen.context).apply {
            key = "cosjun_logout"
            title = "退出登录 / 重新登录"
            summary = if (hasSession) "当前已登录（会话已保存，重启后仍有效）。点此清除后重新登录。"
                      else "未登录。填写账号密码后自动登录并保存会话。"
            setOnPreferenceClickListener {
                prefs.edit().remove(KEY_SESSION).apply()
                loginStamp = 0L
                summary = "登录已清除，下次浏览时用账号密码重新登录。"
                true
            }
        })
    }

    // ------------------------------------------------------------------ login

    private var loginStamp: Long
        get() = prefs.getLong(KEY_LOGIN_STAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LOGIN_STAMP, value).apply()

    private val hasSession: Boolean
        get() = !prefs.getString(KEY_SESSION, "").isNullOrEmpty()

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor { chain ->
                val sid = prefs.getString(KEY_SESSION, "")
                val original = chain.request()
                val req = if (!sid.isNullOrEmpty()
                    && original.url.host.contains("cosjun.cn")
                    && !original.header("Cookie").orEmpty().contains("PHPSESSID")) {
                    original.newBuilder().addHeader("Cookie", "PHPSESSID=$sid").build()
                } else original
                chain.proceed(req)
            }
            .build()
    }

    private suspend fun ensureLoggedIn() = withContext(Dispatchers.IO) {
        val user = prefs.getString(KEY_USER, "")?.trim().orEmpty()
        val pass = prefs.getString(KEY_PASS, "")?.trim().orEmpty()
        if (user.isEmpty() || pass.isEmpty()) return@withContext
        if (hasSession && System.currentTimeMillis() - loginStamp < 30 * 60 * 1000L) return@withContext
        synchronized(this@Cosjun) {
            if (hasSession && System.currentTimeMillis() - loginStamp < 30 * 60 * 1000L) return@synchronized
            try {
                val body = FormBody.Builder()
                    .add("action", "user_login")
                    .add("username", user)
                    .add("password", pass)
                    .add("rememberme", "forever")
                    .build()
                val req = POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, body)
                val resp = client.newCall(req).execute()
                val ok = resp.use {
                    val setCookies = it.headers("Set-Cookie")
                    val sid = setCookies.firstNotNullOfOrNull { c ->
                        Regex("PHPSESSID=([^;]+)").find(c)?.groupValues?.get(1)
                    }
                    if (sid != null) prefs.edit().putString(KEY_SESSION, sid).apply()
                    it.body.string().contains("\"status\":\"1\"")
                }
                loginStamp = if (ok) System.currentTimeMillis() else 0L
                if (ok) docCache.clear()
            } catch (e: Exception) {
                if (!hasSession) loginStamp = 0L
            }
        }
    }

    private data class DocEntry(val at: Long, val doc: Document)
    private val docCache = java.util.concurrent.ConcurrentHashMap<String, DocEntry>()
    private val DOC_TTL_MS = 60_000L

    private suspend fun detailDoc(url: String): Document {
        val cached = docCache[url]
        if (cached != null && System.currentTimeMillis() - cached.at < DOC_TTL_MS) {
            return cached.doc
        }
        val resp = client.newCall(GET(url, headers)).awaitSuccess()
        val doc = resp.use { it.asJsoup() }
        docCache[url] = DocEntry(System.currentTimeMillis(), doc)
        return doc
    }

    private val ajaxHeaders: Headers by lazy {
        Headers.Builder()
            .add("User-Agent", UA)
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", baseUrl)
            .build()
    }

    // ----------------------------------------------------------------- filters

    /** Category selector. "全部" is FIRST and aggregates the four content tabs. */
    private class CategoryFilter : Filter.Select<String>(
        "分区", arrayOf("全部", "COS妹子", "COS本子", "少女日常", "付费专区", "妹子目录")
    )
    private class SortFilter : Filter.Select<String>(
        "排序", arrayOf("新到旧（默认）", "旧到新")
    )
    private class TimeFilter : Filter.Select<String>(
        "时间范围", arrayOf("全部", "最近一周", "最近一月", "最近一年")
    )
    private class TagFilter(name: String, val slug: String) : Filter.CheckBox(name)
    private class TagGroup(tags: List<Pair<String, String>>) :
        Filter.Group<TagFilter>("妹子目录 · COS 名标签（点选后筛选）",
            tags.map { TagFilter(it.first, it.second) })

    /** Raw tag list (name -> /tag/ path) fetched lazily from /tags, cached. */
    @Volatile
    private var tagList: List<Pair<String, String>> = emptyList()

    /** Blocking fetch of /tags. Called from getFilterList() on the IO thread. */
    private fun loadTags(): List<Pair<String, String>> {
        val cached = tagList
        if (cached.isNotEmpty()) return cached
        return try {
            val resp = client.newCall(GET("$baseUrl/tags", headers)).execute()
            val doc = resp.use { it.asJsoup() }
            val out = LinkedHashMap<String, String>()
            doc.select("a[href*=/tag/]").forEach { a ->
                val href = a.attr("href")
                val name = a.text().trim()
                if (name.isNotEmpty() && href.contains("/tag/")) {
                    val path = if (href.startsWith("http")) {
                        href.substringAfter("cosjun.cn")
                    } else href
                    out.putIfAbsent(name, path)
                }
            }
            val list = out.toList()
            if (list.isNotEmpty()) tagList = list
            list
        } catch (e: Exception) {
            tagList // fall back to whatever we had (may be the static seed)
        }
    }

    override fun getFilterList(): FilterList {
        // Try the live /tags directory first (blocks on the IO thread); fall
        // back to the static seed only if the network call fails.
        var tags = loadTags()
        if (tags.isEmpty()) {
            tags = FALLBACK_TAGS.map { it.first to "/tag/${it.second}" }
            tagList = tags
        }
        return FilterList(
            CategoryFilter(),
            SortFilter(),
            TimeFilter(),
            Filter.Separator(),
            TagGroup(tags),
        )
    }

    companion object {
        private const val KEY_USER = "cosjun_user"
        private const val KEY_PASS = "cosjun_pass"
        private const val KEY_SESSION = "cosjun_phpsessid"
        private const val KEY_LOGIN_STAMP = "cosjun_login_stamp"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        /** Four content categories aggregated by "全部". */
        private val CONTENT_CATS = arrayOf("photo", "benzi", "life", "zhuanqu")
        private val CAT_LABELS = arrayOf("全部", "COS妹子", "COS本子", "少女日常", "付费专区", "妹子目录")

        /** Seed tags used only if /tags cannot be fetched on first open. */
        private val FALLBACK_TAGS = listOf(
            "蠢沫沫" to "蠢沫沫", "HaneAme" to "haneame", "水淼aqua" to "水淼aqua",
            "星之迟迟" to "星之迟迟", "日奈娇" to "日奈娇", "神楽坂真冬" to "神楽坂真冬",
            "抖娘利世" to "抖娘利世", "二佐Nisa" to "二佐Nisa", "桜井宁宁" to "桜井宁宁",
            "洛璃LoLiSAMA" to "洛璃LoLiSAMA", "桜桃喵" to "桜桃喵", "雯妹不讲道理" to "雯妹不讲道理",
            "疯猫ss" to "疯猫ss", "ElyEE子" to "elyee", "Natsuko_夏夏子" to "natsuko",
            "抱走莫子" to "抱走莫子", "不呆猫" to "不呆猫", "菌烨tako" to "菌烨tako",
            "花兮_honoka" to "花兮", "瓜希酱" to "瓜希酱", "Meroko_魅瞳" to "meroko",
        )
    }

    /** Category currently being browsed, so search/latest stay in scope. */
    @Volatile
    private var category = CONTENT_CATS[0]

    /** URL tail of the last browse/search, used to build the "next book" chain. */
    @Volatile
    private var browseQueue: List<String> = emptyList()

    /** Publish timestamps by manga url, used to merge "全部" newest-first. */
    private val datesByUrl = ConcurrentHashMap<String, Long>()

    // --------------------------------------------------------------- requests

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", UA)
        .add("Referer", baseUrl)

    private fun catPath(cat: String, page: Int): String =
        if (page <= 1) "$baseUrl/$cat" else "$baseUrl/$cat/page/$page"

    private fun sortSuffix(filters: FilterList): String {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.state ?: 0
        return if (sort == 1) "?orderby=date&order=asc" else ""
    }

    private fun categoryState(filters: FilterList?): Int =
        filters?.filterIsInstance<CategoryFilter>()?.firstOrNull()?.state ?: 0

    private fun selectedTags(filters: FilterList?): List<String> =
        filters?.filterIsInstance<TagGroup>()?.firstOrNull()
            ?.state?.filter { it.state }?.map { it.slug }.orEmpty()

    private fun timeState(filters: FilterList?): Int =
        filters?.filterIsInstance<TimeFilter>()?.firstOrNull()?.state ?: 0

    private fun timeRangeMillis(range: Int): Long = when (range) {
        1 -> 7L * 24 * 3600 * 1000
        2 -> 30L * 24 * 3600 * 1000
        3 -> 365L * 24 * 3600 * 1000
        else -> 0L
    }

    override fun popularMangaRequest(page: Int): Request {
        category = CONTENT_CATS[0]
        return GET(catPath(category, page), headers)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET(catPath(category, page), headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val catIdx = categoryState(filters)
        val tags = selectedTags(filters)
        var url = when {
            query.isNotBlank() -> {
                val q = java.net.URLEncoder.encode(query, "UTF-8")
                if (page <= 1) "$baseUrl/?s=$q" else "$baseUrl/page/$page/?s=$q"
            }
            tags.size == 1 -> {
                val t = tags[0].removePrefix("/tag/")
                if (page <= 1) "$baseUrl/tag/$t" else "$baseUrl/tag/$t/page/$page"
            }
            catIdx in 1..4 -> catPath(CONTENT_CATS[catIdx - 1], page)
            else -> catPath(CONTENT_CATS[0], page) // 全部 / 妹子目录(no tag): catalogue
        }
        url += sortSuffix(filters)
        // Time range is applied client-side in parse (the site has no server filter).
        return GET(url, headers)
    }

    // ------------------------------------------------------------------ parses

    private fun parseList(doc: Document, timeRange: Int): MangasPage {
        val items = doc.select("article.post")
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val now = System.currentTimeMillis()
        val window = timeRangeMillis(timeRange)
        val cutoff = if (window > 0) now - window else 0L
        val mangas = mutableListOf<SManga>()
        for (el in items) {
            val a = el.selectFirst("h2.entry-title a") ?: continue
            val href = a.attr("href")
            val id = href.trimEnd('/').substringAfterLast('/')
            if (id.isEmpty() || id == "page") continue
            val timeEl = el.selectFirst("time[datetime]")
            var posted = 0L
            if (timeEl != null) {
                posted = try {
                    df.parse(timeEl.attr("datetime").substring(0, 19))?.time ?: 0L
                } catch (e: Exception) { 0L }
            }
            if (cutoff > 0 && posted > 0 && posted < cutoff) continue
            val img = el.selectFirst("img[data-src]")?.attr("data-src")
                ?: el.selectFirst("img")?.attr("src")
            val cover = img?.let { timthumbToOriginal(it) }
            val locked = el.selectFirst(".entry-format i.fa-lock") != null
            val cat = href.substringAfter(baseUrl + "/").substringBefore("/")
                .takeIf { it in CONTENT_CATS } ?: category
            val url = "/$cat/$id"
            datesByUrl[url] = posted
            mangas += SManga.create().apply {
                this.url = url
                title = a.text().trim()
                thumbnail_url = cover
                author = title.substringBefore(' ').ifBlank { null }
                description = (el.selectFirst(".entry-excerpt")?.text().orEmpty()) +
                    if (locked) "（需登录查看全量）" else ""
                initialized = false
            }
        }
        browseQueue = mangas.map { it.url }
        val hasNext = doc.selectFirst(".pagination a.next, a.next, .pagination .current~a") != null
        return MangasPage(mangas, hasNext)
    }

    /** Builds "全部": fetch all four content pages, merge, sort newest-first. */
    private suspend fun aggregateAll(page: Int, timeRange: Int): MangasPage {
        val merged = mutableListOf<SManga>()
        var hasNext = false
        for (cat in CONTENT_CATS) {
            try {
                val url = catPath(cat, page)
                val resp = client.newCall(GET(url, headers)).awaitSuccess()
                val part = resp.use { parseList(it.asJsoup(), timeRange) }
                merged.addAll(part.mangas)
                hasNext = hasNext || part.hasNextPage
            } catch (e: Exception) {
                // Keep whatever categories succeeded - do not blank the page.
            }
        }
        merged.sortByDescending { datesByUrl[it.url] ?: 0L }
        browseQueue = merged.map { it.url }
        return MangasPage(merged, hasNext)
    }

    private fun timthumbToOriginal(url: String): String {
        if (!url.contains("timthumb.php")) return url
        val idx = url.indexOf("src=")
        if (idx < 0) return url
        var raw = url.substring(idx + 4)
        val end = raw.indexOf('&')
        if (end > 0) raw = raw.substring(0, end)
        return raw.replace("&#038;", "&").replace("%3A", ":").replace("%2F", "/")
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        ensureLoggedIn()
        // The default catalogue ("全部") aggregates the four content tabs.
        return aggregateAll(page, 0)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        ensureLoggedIn()
        if (category == CONTENT_CATS[0]) return aggregateAll(page, 0)
        val resp = client.newCall(latestUpdatesRequest(page)).awaitSuccess()
        return resp.use { parseList(it.asJsoup(), 0) }
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        ensureLoggedIn()
        val catIdx = categoryState(filters)
        val tags = selectedTags(filters)
        // Multiple COS-name tags, or "妹子目录" with tags selected -> aggregate
        // each tag page and merge. Single tag / category goes through request.
        if (tags.size > 1) {
            val merged = mutableListOf<SManga>()
            for (t in tags) {
                try {
                    val path = t.removePrefix("/tag/")
                    val url = if (page <= 1) "$baseUrl/tag/$path" else "$baseUrl/tag/$path/page/$page"
                    val resp = client.newCall(GET(url + sortSuffix(filters), headers)).awaitSuccess()
                    merged.addAll(resp.use { parseList(it.asJsoup(), timeState(filters)).mangas })
                } catch (e: Exception) { }
            }
            merged.sortByDescending { datesByUrl[it.url] ?: 0L }
            browseQueue = merged.map { it.url }
            return MangasPage(merged, false)
        }
        if (catIdx == 0 && query.isBlank() && tags.isEmpty()) {
            return aggregateAll(page, timeState(filters))
        }
        val time = timeState(filters)
        val resp = client.newCall(searchMangaRequest(page, query, filters)).awaitSuccess()
        val result = resp.use { parseList(it.asJsoup(), time) }
        return result
    }

    // ----------------------------------------------------------------- details

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        ensureLoggedIn()
        val detailResult = if (fetchDetails) {
            parseDetails(detailDoc(baseUrl + manga.url), manga)
        } else manga
        val chapterResult = if (fetchChapters) chapterChain(detailResult) else chapters
        return SMangaUpdate(detailResult, chapterResult)
    }

    private fun parseDetails(doc: Document, manga: SManga): SManga {
        val h1 = doc.selectFirst("h1.entry-title")?.text()
            ?: doc.selectFirst("h1")?.text()?.trim()
        val excerpt = doc.selectFirst(".entry-content p")?.text().orEmpty()
        val cosName = doc.selectFirst(".entry-content a[href*=/tag/]")?.text()?.trim()
        val genre = doc.select(".entry-content a[href*=/tag/]")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
            .take(20).joinToString(", ")
        return manga.apply {
            title = h1?.trim() ?: title
            author = cosName ?: title.substringBefore(' ').ifBlank { null }
            description = excerpt
            this.genre = genre.ifBlank { null }
            initialized = true
        }
    }

    private fun chapterChain(manga: SManga): List<SChapter> {
        val self = manga.url
        val queue = browseQueue.ifEmpty { listOf(self) }
        val seq = if (self in queue) {
            val i = queue.indexOf(self)
            queue.drop(i)
        } else listOf(self) + queue
        return seq.mapIndexed { idx, url ->
            SChapter.create().apply {
                this.url = url
                name = if (idx == 0) "图片目录" else "下一本 ${idx + 1}"
                chapter_number = idx.toFloat()
            }
        }
    }

    // ------------------------------------------------------------------- pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        ensureLoggedIn()
        return parsePages(detailDoc(baseUrl + chapter.url))
    }

    private fun parsePages(doc: Document): List<Page> {
        val pages = mutableListOf<Page>()
        doc.select("video.wp-video-shortcode source[src]").forEach { src ->
            val v = src.attr("src").substringBefore('?')
            if (v.contains(".mp4")) pages += Page(pages.size, imageUrl = v)
        }
        val galleries = doc.select("div.gallery")
        val full = galleries.maxByOrNull { it.select(".gallery-icon a").size }
        full?.select(".gallery-icon a[href]")?.forEach { a ->
            val href = a.attr("href")
            if (href.contains("/wp-content/uploads/") &&
                href.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif|bmp)(\\?.*)?$", RegexOption.IGNORE_CASE))) {
                pages += Page(pages.size, imageUrl = href)
            }
        }
        return pages
    }

    // -------------------------------------------------- HttpSource suspension

    override fun popularMangaParse(response: Response): MangasPage =
        response.use { parseList(it.asJsoup(), 0) }

    override fun searchMangaParse(response: Response): MangasPage =
        response.use { parseList(it.asJsoup(), 0) }

    override fun latestUpdatesParse(response: Response): MangasPage =
        response.use { parseList(it.asJsoup(), 0) }

    override fun mangaDetailsParse(response: Response): SManga =
        response.use { parseDetails(it.asJsoup(), SManga.create()) }

    override fun chapterListParse(response: Response): List<SChapter> = emptyList()

    override fun pageListParse(response: Response): List<Page> =
        response.use { parsePages(it.asJsoup()) }
}
