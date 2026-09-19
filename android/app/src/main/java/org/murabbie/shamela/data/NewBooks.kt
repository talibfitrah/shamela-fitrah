package org.murabbie.shamela.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** كتاب على موقع shamela.ws غير موجود في فهرس المكتبة المحلي. */
data class NewBook(
    val id: Int, val name: String, val authorId: Int?, val authorName: String, val card: String,
    val categoryId: Int, val categoryName: String,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("authorId", authorId ?: 0).put("authorName", authorName)
        .put("card", card).put("categoryId", categoryId).put("categoryName", categoryName)
    companion object {
        fun fromJson(o: JSONObject) = NewBook(o.getInt("id"), o.getString("name"), o.optInt("authorId").takeIf { it > 0 }, o.optString("authorName"),
            o.optString("card"), o.optInt("categoryId"), o.optString("categoryName"))
    }
}

/** نتيجة فحص الموقع كاملًا. */
data class NewBooksScan(val scannedAt: Long, val siteTotal: Int, val localTotal: Int, val categoriesScanned: Int, val books: List<NewBook>) {
    fun toJson(): JSONObject = JSONObject().put("scannedAt", scannedAt).put("siteTotal", siteTotal).put("localTotal", localTotal)
        .put("categoriesScanned", categoriesScanned).put("books", JSONArray().apply { books.forEach { put(it.toJson()) } })
    companion object {
        fun fromJson(o: JSONObject): NewBooksScan {
            val arr = o.getJSONArray("books")
            return NewBooksScan(o.getLong("scannedAt"), o.getInt("siteTotal"), o.getInt("localTotal"), o.optInt("categoriesScanned"),
                (0 until arr.length()).map { NewBook.fromJson(arr.getJSONObject(it)) })
        }
    }
}

sealed class ScanState {
    object Idle : ScanState()
    data class Running(val done: Int, val total: Int, val found: Int) : ScanState()
    data class Failed(val message: String) : ScanState()
}

/**
 * نظام البحث عن مستجدات المكتبة الشاملة: يقرأ صفحات الأقسام الأربعين على shamela.ws (كل قسم يعرض كتبه كلها)،
 * ويقارنها بفهرس المكتبة المحلي، فيُخرج كل كتاب أُضيف إلى الموقع ولم يصل إلى الحزمة بعد.
 * كل كتاب مكتشَف يُسجَّل كتابًا إضافيًا في قاعدة المستخدم فيُفتح ويُقرأ فورًا (يُجلب من الموقع صفحةً صفحة) أو يُنزَّل.
 */
class NewBooks(private val library: Library, private val userDb: UserDb) {

    private val cacheFile: File get() = File(library.baseDir, "newbooks.json")

    private val _scan = MutableStateFlow<NewBooksScan?>(null)
    val scan: StateFlow<NewBooksScan?> = _scan
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state

    init { runCatching { if (cacheFile.exists()) _scan.value = NewBooksScan.fromJson(JSONObject(cacheFile.readText())) } }

    /** هل مضى على آخر فحص أكثر من [maxAgeMs]؟ */
    fun stale(maxAgeMs: Long = 24 * 3600 * 1000L): Boolean = (_scan.value?.scannedAt ?: 0L) < System.currentTimeMillis() - maxAgeMs

    private val itemRegex = Regex("<div class=\"book_item[^\"]*\">\\s*<a href=\"[^\"]*/book/(\\d+)\"[^>]*>(.*?)</a>\\s*\\[<a href=\"[^\"]*/author/(\\d+)\"[^>]*>(.*?)</a>]\\s*<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
    private val catRegex = Regex("href=\"[^\"]*/category/(\\d+)\"[^>]*>\\s*(?:[٠-٩0-9]+\\.\\s*)?(.*?)<span", RegexOption.DOT_MATCHES_ALL)

    private fun parseItems(html: String, catId: Int, catName: String): List<NewBook> {
        val start = html.indexOf("id=\"cat_books\"")
        val section = if (start >= 0) html.substring(start) else html
        return itemRegex.findAll(section).map { m ->
            NewBook(
                id = m.groupValues[1].toInt(),
                name = ShamelaWeb.unescape(ArabicText.stripTags(m.groupValues[2])).trim(),
                authorId = m.groupValues[3].toIntOrNull(),
                authorName = ShamelaWeb.unescape(ArabicText.stripTags(m.groupValues[4])).trim(),
                card = ShamelaWeb.unescape(m.groupValues[5].replace(Regex("<br\\s*/?>"), "\n").let { ArabicText.stripTags(it) }).trim(),
                categoryId = catId, categoryName = catName,
            )
        }.toList()
    }

    /** فحص الموقع كاملًا. يعيد النتيجة ويحفظها. */
    suspend fun scanSite(): NewBooksScan = withContext(Dispatchers.IO) {
        if (_state.value is ScanState.Running) return@withContext _scan.value ?: throw IllegalStateException("الفحص جارٍ")
        _state.value = ScanState.Running(0, 0, 0)
        try {
            val site = org.murabbie.shamela.BuildConfig.SHAMELA_SITE
            val home = NasHttp.fetchText("$site/", attempts = 4, browserUa = true)
            val cats = LinkedHashMap<Int, String>()
            catRegex.findAll(home).forEach { m -> val id = m.groupValues[1].toInt(); if (!cats.containsKey(id)) cats[id] = ShamelaWeb.unescape(ArabicText.stripTags(m.groupValues[2])).trim() }
            if (cats.isEmpty()) throw IllegalStateException("تعذّر قراءة أقسام الموقع")
            val local = library.indexedIds()
            val done = AtomicInteger(0); val found = AtomicInteger(0)
            _state.value = ScanState.Running(0, cats.size, 0)
            val siteIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
            val results = coroutineScope {
                val sem = Semaphore(4)
                cats.map { (id, name) ->
                    async {
                        sem.withPermit {
                            val html = NasHttp.fetchText("$site/category/$id", attempts = 4, browserUa = true)
                            val items = parseItems(html, id, name)
                            items.forEach { siteIds.add(it.id) }
                            val fresh = items.filter { it.id !in local }
                            found.addAndGet(fresh.size)
                            _state.value = ScanState.Running(done.incrementAndGet(), cats.size, found.get())
                            fresh
                        }
                    }
                }.map { it.await() }
            }
            val books = results.flatten().distinctBy { it.id }.sortedByDescending { it.id }
            // سجّلها كتبًا إضافية لتُفتح وتُقرأ وتُنزَّل مثل كتب الفهرس
            for (b in books) if (userDb.extraBook(b.id) == null) userDb.addExtraBook(b.id, mapOf(
                "book_name" to b.name, "author_name" to b.authorName, "author_id" to (b.authorId?.toString() ?: ""), "category_name" to b.categoryName, "card" to b.card))
            val res = NewBooksScan(System.currentTimeMillis(), siteIds.size, local.size, cats.size, books)
            _scan.value = res
            runCatching { cacheFile.writeText(res.toJson().toString()) }
            _state.value = ScanState.Idle
            res
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) { _state.value = ScanState.Idle; throw e }
            _state.value = ScanState.Failed(e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /** بعد تحديث الفهرس من NAS: أسقط ما صار في الفهرس. */
    suspend fun pruneIndexed() {
        val s = _scan.value ?: return
        val local = library.indexedIds()
        val kept = s.books.filter { it.id !in local }
        if (kept.size != s.books.size) { val r = s.copy(books = kept, localTotal = local.size); _scan.value = r; runCatching { cacheFile.writeText(r.toJson().toString()) } }
    }
}
