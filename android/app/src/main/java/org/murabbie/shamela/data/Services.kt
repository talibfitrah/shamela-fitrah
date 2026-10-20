package org.murabbie.shamela.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream

/** حالة قاعدة الخدمات */
sealed class ServicesState {
    object NotInstalled : ServicesState()
    data class Downloading(val message: String, val bytes: Long, val total: Long) : ServicesState()
    object Ready : ServicesState()
    data class Error(val message: String) : ServicesState()
}

data class Rawi(val id: Int, val name: String, val full: String, val death: Int?, val jarh: List<JarhEntry>, val details: List<Pair<String, String>>) {
    val deathLabel: String get() = death?.takeIf { it > 0 }?.let { "ت ${ArabicText.arabicDigits(it)} هـ" } ?: ""
}
data class JarhEntry(val critic: String, val items: List<Pair<String, String>>)
data class ServiceRef(val bookId: Int, val pageId: Int, val bookName: String = "", val extra: Int = 0)
data class Sura(val id: Int, val name: String, val ayat: Int, val firstAya: Int)
data class Aya(val id: Int, val sura: Int, val aya: Int, val text: String, val uthmani: String)

/**
 * خدمات الشاملة (كما في نسخة ويندوز) من ملف واحد services.db يُنزَّل عند الطلب من NAS:
 * تراجم الرواة، تخريج الحديث في الكتب العشرة، أسانيد الأحاديث ومرويات الراوي، القرآن الكريم وتفسير الآية،
 * والبحث بالجذر (الاشتقاق).
 */
class Services(private val library: Library) {

    val file: File get() = File(library.baseDir, "services.db")
    private val _state = MutableStateFlow<ServicesState>(if (file.exists() && file.length() > 0) ServicesState.Ready else ServicesState.NotInstalled)
    val state: StateFlow<ServicesState> = _state
    val installed: Boolean get() = _state.value is ServicesState.Ready

    private var db: Db? = null
    private val mutex = Mutex()
    private suspend fun db(): Db = mutex.withLock { db?.takeIf { !it.closed } ?: Db(file.path, readOnly = true).also { db = it } }
    private suspend fun closeDb() = mutex.withLock { db?.close(); db = null }

    /** تنزيل services.db.gz وتثبيته */
    suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        try {
            NasHttp.clearLog()
            _state.value = ServicesState.Downloading("جلب ملف الوصف…", 0, -1)
            var m = library.manifestOrFetch()
            if (m.services == null) m = library.fetchManifest()
            val mf = m.services ?: throw IllegalStateException("ملف الوصف على الخادم لا يحوي قاعدة الخدمات")
            val gz = File(library.tmpDir, "services.db.gz")
            NasHttp.download(mf.url, gz, mf.size) { b, t -> _state.value = ServicesState.Downloading("تنزيل قاعدة الخدمات", b, t) }
            if (mf.sha256.isNotBlank()) {
                _state.value = ServicesState.Downloading("التحقق من الملف…", 0, -1)
                val h = NasHttp.sha256(gz)
                if (!h.equals(mf.sha256, true)) { gz.delete(); throw IllegalStateException("بصمة الملف لا تطابق") }
            }
            _state.value = ServicesState.Downloading("تثبيت الخدمات…", 0, -1)
            val tmp = File(library.tmpDir, "services.db.tmp")
            GZIPInputStream(gz.inputStream().buffered(1 shl 16)).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            closeDb()
            if (!tmp.renameTo(file)) { tmp.copyTo(file, true); tmp.delete() }
            gz.delete()
            _state.value = ServicesState.Ready
            true
        } catch (e: Exception) {
            // مغادرة الشاشة تلغي التنزيل: تُعاد الحالة وإلا بقيت «جارٍ التنزيل» بلا زر إعادة
            if (e is kotlinx.coroutines.CancellationException) { _state.value = if (file.exists() && file.length() > 0) ServicesState.Ready else ServicesState.NotInstalled; throw e }
            _state.value = if (file.exists() && file.length() > 0) ServicesState.Ready else ServicesState.Error(e.message ?: "خطأ")
            false
        }
    }

    suspend fun uninstall() { closeDb(); file.delete(); _state.value = ServicesState.NotInstalled }

    suspend fun counts(): Map<String, Long> = mapOf(
        "rawi" to db().count("select count(*) from rawi"), "hadith" to db().count("select count(distinct key_id) from hadith_link"),
        "esnad" to db().count("select count(*) from esnad"), "aya" to db().count("select count(*) from aya"), "roots" to db().count("select count(*) from roots"),
    )

    // ---------- الرواة ----------

    private fun parseRawi(r: Db.Row): Rawi {
        val jarh = ArrayList<JarhEntry>()
        runCatching {
            val a = JSONArray(r.text(4))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val items = o.optJSONArray("items") ?: JSONArray()
                jarh.add(JarhEntry(o.optString("critic"), (0 until items.length()).map { val it2 = items.getJSONObject(it); it2.optString("text") to it2.optString("source") }))
            }
        }
        val details = ArrayList<Pair<String, String>>()
        runCatching {
            val a = JSONArray(r.text(5))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); details.add(o.optString("label") to o.optString("value")) }
        }
        return Rawi(r.int(0), r.text(1), r.text(2), r.intOrNull(3), jarh, details)
    }

    suspend fun rawi(id: Int): Rawi? = db().queryOne("select id,name,full,death,jarh,details from rawi where id=?", id, mapper = ::parseRawi)

    suspend fun rawiNames(ids: Collection<Int>): Map<Int, String> {
        if (ids.isEmpty()) return emptyMap()
        return db().query("select id,name from rawi where id in (${ids.joinToString(",")})") { it.int(0) to it.text(1) }.toMap()
    }

    suspend fun searchRawi(q: String, limit: Int = 200): List<Rawi> {
        val fts = ArabicText.ftsQuery(q, 0, true) ?: return emptyList()
        return db().query("select r.id,r.name,r.full,r.death,r.jarh,r.details from rawi_fts f join rawi r on r.id=f.rowid where rawi_fts match ? order by rank limit ?", fts, limit, mapper = ::parseRawi)
    }

    /** مرويات الراوي في الكتب العشرة (مواضع الأحاديث التي هو في إسنادها) */
    suspend fun rawiPages(id: Int, limit: Int = 2000): List<ServiceRef> = withNames(
        db().query("select book_id,page_id,hadith from rawi_page where rawi_id=? order by book_id,page_id limit ?", id, limit) { ServiceRef(it.int(0), it.int(1), extra = it.int(2)) })

    suspend fun rawiPagesCount(id: Int): Long = db().count("select count(*) from rawi_page where rawi_id=?", id)

    // ---------- الحديث ----------

    /** أرقام أحاديث الصفحة من عمود services (hadeeth) */
    fun hadithKeys(services: String?): List<Int> = keys(services, "hadeeth")
    fun ayaKeys(services: String?): List<Int> = keys(services, "ayat")
    private fun keys(services: String?, key: String): List<Int> {
        if (services.isNullOrBlank()) return emptyList()
        return runCatching { val a = JSONObject(services).optJSONArray(key) ?: return emptyList(); (0 until a.length()).map { a.getInt(it) } }.getOrDefault(emptyList())
    }
    /** أرقام رواة إسناد الصفحة من عمود services (esnad: "01 s 4677 4494 ...") */
    fun esnadIds(services: String?): List<List<Int>> {
        if (services.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONObject(services).optJSONArray("esnad") ?: return emptyList()
            (0 until a.length()).map { i -> a.getString(i).split(' ').drop(2).mapNotNull { it.toIntOrNull() } }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    /** تخريج الحديث: مواضع الحديث (بمفتاحه) في الكتب العشرة */
    suspend fun hadithOccurrences(keys: List<Int>, exceptBook: Int = -1, exceptPage: Int = -1): List<ServiceRef> {
        if (keys.isEmpty()) return emptyList()
        val rows = db().query("select distinct key_id,book_id,page_id from hadith_link where key_id in (${keys.joinToString(",")}) order by key_id,book_id,page_id") { ServiceRef(it.int(1), it.int(2), extra = it.int(0)) }
        return withNames(rows.filter { !(it.bookId == exceptBook && it.pageId == exceptPage) })
    }

    /** الكتب المشمولة بخدمة التخريج */
    suspend fun hadithBooks(): List<Book> = library.booksByIds(db().query("select book_id from hadith_book") { it.int(0) })

    /** أسانيد الصفحة (من قاعدة الخدمات إن لم تكن في services الصفحة) */
    suspend fun esnadFor(bookId: Int, pageId: Int): List<List<Int>> =
        db().query("select chain from esnad where book_id=? and page_id=?", bookId, pageId) { it.text(0).split(' ').mapNotNull { s -> s.toIntOrNull() } }.filter { it.isNotEmpty() }

    // ---------- القرآن والتفسير ----------

    suspend fun suras(): List<Sura> = db().query("select id,name,ayat,first_aya from sura order by id") { Sura(it.int(0), it.text(1), it.int(2), it.int(3)) }
    suspend fun sura(id: Int): Sura? = db().queryOne("select id,name,ayat,first_aya from sura where id=?", id) { Sura(it.int(0), it.text(1), it.int(2), it.int(3)) }
    private fun mapAya(r: Db.Row) = Aya(r.int(0), r.int(1), r.int(2), r.text(3), r.text(4))
    suspend fun ayat(sura: Int): List<Aya> = db().query("select id,sura,aya,text,uthmani from aya where sura=? order by aya", sura, mapper = ::mapAya)
    suspend fun aya(id: Int): Aya? = db().queryOne("select id,sura,aya,text,uthmani from aya where id=?", id, mapper = ::mapAya)
    suspend fun ayaAt(sura: Int, n: Int): Aya? = db().queryOne("select id,sura,aya,text,uthmani from aya where sura=? and aya=?", sura, n, mapper = ::mapAya)
    suspend fun ayatByIds(ids: List<Int>): List<Aya> = if (ids.isEmpty()) emptyList() else db().query("select id,sura,aya,text,uthmani from aya where id in (${ids.joinToString(",")}) order by id", mapper = ::mapAya)
    suspend fun searchAyat(q: String, limit: Int = 300): List<Aya> {
        val fts = ArabicText.ftsQuery(q, 0, false) ?: return emptyList()
        return db().query("select a.id,a.sura,a.aya,a.text,a.uthmani from aya_fts f join aya a on a.id=f.rowid where aya_fts match ? order by a.id limit ?", fts, limit, mapper = ::mapAya)
    }
    /** مواضع تفسير الآية في كتب التفسير المشمولة */
    suspend fun tafsirFor(ayaId: Int): List<ServiceRef> = withNames(db().query("select distinct book_id,page_id from tafsir_link where aya_id=? order by book_id,page_id", ayaId) { ServiceRef(it.int(0), it.int(1), extra = ayaId) })
    suspend fun tafsirBooks(): List<Book> = library.booksByIds(db().query("select book_id from tafsir_book") { it.int(0) })

    // ---------- الجذور ----------

    /** جذر (أو جذور) كلمة */
    suspend fun rootsOf(word: String): List<String> {
        val w = ArabicText.normalize(word).trim()
        if (w.isEmpty()) return emptyList()
        val r = db().queryOne("select root from roots where token=?", w) { it.text(0) } ?: return emptyList()
        return r.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
    /** كل الكلمات المشتقة من جذر */
    suspend fun tokensOfRoot(root: String, limit: Int = 400): List<String> =
        db().query("select token from roots where root=? or root like ? or root like ? or root like ? limit ?", root, "$root,%", "%,$root", "%,$root,%", limit) { it.text(0) }

    private suspend fun withNames(refs: List<ServiceRef>): List<ServiceRef> {
        if (refs.isEmpty()) return refs
        val books = library.booksByIds(refs.map { it.bookId }.toSet()).associateBy { it.id }
        return refs.map { it.copy(bookName = books[it.bookId]?.name ?: "كتاب ${it.bookId}") }
    }
}
