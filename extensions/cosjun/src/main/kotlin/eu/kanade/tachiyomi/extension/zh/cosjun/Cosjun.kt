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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Cosjun (cosjun.cn) — WordPress/ripro image gallery source.
 *
 * One catalogue entry is one picture folder (a set of images + optional video),
 * not a multi-chapter comic. To make "next page" flow into the next item in the
 * exact order the user was browsing, the last browse/search result URL list is
 * cached in [browseQueue] and [fetchChapterList] exposes the tail of it as
 * pseudo-chapters, with the current item first.
 *
 * Login: credentials are entered in the source settings (EditTextPreference).
 * [ensureLoggedIn] posts to admin-ajax.php?action=user_login once and relies on
 * the shared cookie jar (PHPSESSID / wordpress_logged_in_*) for all later calls.
 */
class Cosjun : HttpSource(), ConfigurableSource {

    override val name = "COS君"
    override val baseUrl = "https://www.cosjun.cn"
    override val lang = "zh"
    override val supportsLatest = true
    override val versionId = 1

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

    /** Last successful login time, PERSISTED. An in-memory-only stamp resets to
     *  0 on process restart while [hasSession] stays true (session is in prefs),
     *  which made every request re-login synchronously - the "everything slow"
     *  bug. Persisting it keeps the 30-minute throttle across restarts. */
    private var loginStamp: Long
        get() = prefs.getLong(KEY_LOGIN_STAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LOGIN_STAMP, value).apply()

    /** Whether a persisted login session is known to work. Once we log in
     *  successfully the PHPSESSID is saved to prefs and injected on every
     *  request via [client], so login survives process restarts ("permanent"). */
    private val hasSession: Boolean
        get() = !prefs.getString(KEY_SESSION, "").isNullOrEmpty()

    // Inject the persisted PHPSESSID into every cosjun.cn request. Built lazily
    // on top of the shared client so it keeps the host's cookie jar / cf_clearance.
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
        // A persisted session means we are already logged in; only re-login on
        // a 30-minute refresh cadence (or when the session is not known good).
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
                    // Capture PHPSESSID from Set-Cookie (one or more headers).
                    val setCookies = it.headers("Set-Cookie")
                    val sid = setCookies.firstNotNullOfOrNull { c ->
                        Regex("PHPSESSID=([^;]+)").find(c)?.groupValues?.get(1)
                    }
                    if (sid != null) prefs.edit().putString(KEY_SESSION, sid).apply()
                    it.body.string().contains("\"status\":\"1\"")
                }
                loginStamp = if (ok) System.currentTimeMillis() else 0L
                if (ok) docCache.clear() // anonymous preview -> full gallery
            } catch (e: Exception) {
                // Keep any previously persisted session on transient failures.
                if (!hasSession) loginStamp = 0L
            }
        }
    }

    /** A detail page is fetched for both metadata and the image list. Cache the
     *  parsed HTML briefly so opening a manga and then its picture folder does
     *  not download the same large page twice (the main cause of "slow to open"
     *  and "slow after switching"). Cleared on every (re)login because the
     *  logged-in page has the full gallery while the anonymous one has 4. */
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

    private class SortFilter : Filter.Select<String>(
        "排序", arrayOf("新到旧（默认）", "旧到新")
    )
    private class TimeFilter : Filter.Select<String>(
        "时间范围", arrayOf("全部", "最近一周", "最近一月")
    )
    private class TagFilter(name: String, val slug: String) : Filter.CheckBox(name)
    private class TagGroup : Filter.Group<TagFilter>("标签（COS 名）", TAG_LIST.map { TagFilter(it, it) })

    override fun getFilterList() = FilterList(
        SortFilter(),
        TimeFilter(),
        Filter.Separator(),
        TagGroup(),
    )

    companion object {
        private const val KEY_USER = "cosjun_user"
        private const val KEY_PASS = "cosjun_pass"
        private const val KEY_SESSION = "cosjun_phpsessid"
        private const val KEY_LOGIN_STAMP = "cosjun_login_stamp"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        // Popular COS names used as tag shortcuts; /tags lists the full set, but
        // a compact set keeps the filter UI usable. Each value is the URL-encoded
        // tag used by the site.
        private val TAG_LIST = listOf(
            "ElyEE子", "蠢沫沫", "Natsuko_夏夏子", "抱走莫子", "不呆猫",
            "菌烨tako", "水淼aqua", "花兮_honoka", "瓜希酱", "Meroko_魅瞳",
        )
        private const val CAT_PHOTO = "photo"
    }

    /** Category currently being browsed, so search/latest stay in scope. */
    @Volatile
    private var category = CAT_PHOTO

    /** URL tail of the last browse/search, used to build the "next book" chain. */
    @Volatile
    private var browseQueue: List<String> = emptyList()

    // --------------------------------------------------------------- requests

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", UA)
        .add("Referer", baseUrl)

    private fun catPath(cat: String, page: Int): String =
        if (page <= 1) "$baseUrl/$cat" else "$baseUrl/$cat/page/$page"

    private fun sortParams(filters: FilterList, url: String): String {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.state ?: 0
        return if (sort == 1) "$url?orderby=date&order=asc" else url
    }

    override fun popularMangaRequest(page: Int): Request {
        category = CAT_PHOTO
        return GET(catPath(category, page), headers)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET(catPath(category, page), headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val time = filters.filterIsInstance<TimeFilter>().firstOrNull()?.state ?: 0
        val tags = filters.filterIsInstance<TagGroup>().firstOrNull()
            ?.state?.filter { it.state }?.map { it.slug }.orEmpty()

        var url = when {
            query.isNotBlank() -> {
                val q = java.net.URLEncoder.encode(query, "UTF-8")
                if (page <= 1) "$baseUrl/?s=$q" else "$baseUrl/page/$page/?s=$q"
            }
            tags.size == 1 -> {
                val t = java.net.URLEncoder.encode(tags[0], "UTF-8")
                if (page <= 1) "$baseUrl/tag/$t" else "$baseUrl/tag/$t/page/$page"
            }
            else -> catPath(category, page)
        }
        url = sortParams(filters, url)
        // Time range is applied client-side in parse (the site has no server filter).
        return GET(url, headers)
    }

    // ------------------------------------------------------------------ parses

    private fun parseList(doc: Document, timeRange: Int): MangasPage {
        val items = doc.select("article.post")
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val now = System.currentTimeMillis()
        val cutoff = when (timeRange) {
            1 -> now - 7L * 24 * 3600 * 1000
            2 -> now - 30L * 24 * 3600 * 1000
            else -> 0L
        }
        val mangas = mutableListOf<SManga>()
        for (el in items) {
            val a = el.selectFirst("h2.entry-title a") ?: continue
            val href = a.attr("href")
            val id = href.trimEnd('/').substringAfterLast('/')
            if (id.isEmpty() || id == "page") continue
            val timeEl = el.selectFirst("time[datetime]")
            if (cutoff > 0 && timeEl != null) {
                val t = try {
                    df.parse(timeEl.attr("datetime").substring(0, 19))?.time ?: 0L
                } catch (e: Exception) { 0L }
                if (t in 1 until cutoff) continue
            }
            val img = el.selectFirst("img[data-src]")?.attr("data-src")
                ?: el.selectFirst("img")?.attr("src")
            val cover = img?.let { timthumbToOriginal(it) }
            val locked = el.selectFirst(".entry-format i.fa-lock") != null
            mangas += SManga.create().apply {
                url = "/$category/$id"
                title = a.text().trim()
                thumbnail_url = cover
                author = title.substringBefore(' ').ifBlank { null }
                description = (el.selectFirst(".entry-excerpt")?.text().orEmpty()) +
                    if (locked) "（需登录查看全量）" else ""
                initialized = false
            }
        }
        // Remember this list order for the next-book chain.
        browseQueue = mangas.map { it.url }
        val hasNext = doc.selectFirst(".pagination a.next, a.next, .pagination .current~a") != null
        return MangasPage(mangas, hasNext)
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
        val resp = client.newCall(popularMangaRequest(page)).awaitSuccess()
        return resp.use { parseList(it.asJsoup(), 0) }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        ensureLoggedIn()
        val resp = client.newCall(latestUpdatesRequest(page)).awaitSuccess()
        return resp.use { parseList(it.asJsoup(), 0) }
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        ensureLoggedIn()
        val time = filters.filterIsInstance<TimeFilter>().firstOrNull()?.state ?: 0
        val resp = client.newCall(searchMangaRequest(page, query, filters)).awaitSuccess()
        return resp.use { parseList(it.asJsoup(), time) }
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
        // The first tag link is the COS name; surface it as the author so the
        // user can tap it to filter/search that name (see getSearchManga).
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

    /**
     * One entry = one picture folder. Expose the current item plus the tail of
     * the browse queue as pseudo chapters, so crossing the last image of a book
     * advances into the NEXT book in the exact order the user browsed/searched.
     */
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
        // Video first (it appears between the preview gallery and the full one).
        doc.select("video.wp-video-shortcode source[src]").forEach { src ->
            val v = src.attr("src").substringBefore('?')
            if (v.contains(".mp4")) pages += Page(pages.size, imageUrl = v)
        }
        // Use the LAST / largest gallery: when logged in it is the full set;
        // when anonymous it is only the 4-image preview gallery.
        val galleries = doc.select("div.gallery")
        val full = galleries.maxByOrNull { it.select(".gallery-icon a").size }
        full?.select(".gallery-icon a[href]")?.forEach { a ->
            val href = a.attr("href")
            if (href.contains("/wp-content/uploads/") && href.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif|bmp)(\\?.*)?$", RegexOption.IGNORE_CASE))) {
                pages += Page(pages.size, imageUrl = href)
            }
        }
        return pages
    }

    // -------------------------------------------------- HttpSource suspension

    // Route the abstract suspend APIs through our request/parse pairs.
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
