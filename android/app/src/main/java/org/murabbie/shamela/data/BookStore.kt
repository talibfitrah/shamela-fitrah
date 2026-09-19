package org.murabbie.shamela.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * فتح قواعد الكتب المثبَّتة (books/<id>.db) مع ذاكرة LRU لعدد محدود من الاتصالات المفتوحة.
 * كل قاعدة كتاب تحوي: page(id, part, page, number, body, foot, services) و title(id, page, parent, text)
 * و page_fts / title_fts (FTS5 بلا محتوى على النص المطبَّع) و meta(key, value).
 */
object BookStore {
    private const val MAX_OPEN = 6
    private val open = LinkedHashMap<Int, Db>(16, 0.75f, true)
    private val mutex = Mutex()

    suspend fun get(id: Int, file: File): Db = mutex.withLock {
        open[id]?.takeIf { !it.closed }?.let { return it }
        if (!file.exists()) throw IllegalStateException("الكتاب غير مثبَّت")
        val db = Db(file.path, readOnly = true)
        open[id] = db
        while (open.size > MAX_OPEN) {
            val eldest = open.entries.first()
            eldest.value.closeSuspend(); open.remove(eldest.key)
        }
        db
    }

    suspend fun close(id: Int) = mutex.withLock { open.remove(id)?.closeSuspend() }
    suspend fun closeAll() = mutex.withLock { open.values.forEach { it.closeSuspend() }; open.clear() }
}

/** واجهة كتاب مفتوح — تحصل على الاتصال عند كل استعلام (فلا تتأثر بإغلاق LRU). */
class BookReader(val bookId: Int, private val file: File) {
    private suspend fun db(): Db = BookStore.get(bookId, file)

    suspend fun pageCount(): Int = db().count("select count(*) from page").toInt()

    suspend fun firstPageId(): Int = db().queryOne("select min(id) from page") { it.int(0) } ?: 1
    suspend fun lastPageId(): Int = db().queryOne("select max(id) from page") { it.int(0) } ?: 1

    suspend fun page(id: Int): Page? = db().queryOne("select id, part, page, number, body, foot, services from page where id=?", id) { mapPage(it) }

    suspend fun nextPageId(id: Int): Int? = db().queryOne("select id from page where id>? order by id limit 1", id) { it.int(0) }
    suspend fun prevPageId(id: Int): Int? = db().queryOne("select id from page where id<? order by id desc limit 1", id) { it.int(0) }

    /** ترتيب الصفحة (1..n) — للتقدم في الكتاب */
    suspend fun ordinal(id: Int): Int = db().count("select count(*) from page where id<=?", id).toInt()

    /** معرّف الصفحة بالجزء ورقم الصفحة المطبوعة */
    suspend fun pageIdFor(part: String?, page: Int): Int? =
        if (part.isNullOrBlank()) db().queryOne("select id from page where page=? order by id limit 1", page) { it.int(0) }
        else db().queryOne("select id from page where part=? and page=? order by id limit 1", part, page) { it.int(0) }
            ?: db().queryOne("select id from page where page=? order by id limit 1", page) { it.int(0) }

    suspend fun parts(): List<String> = db().query("select distinct part from page where part is not null and part<>'' order by cast(part as integer), part") { it.text(0) }

    suspend fun pageRangeForPart(part: String?): Pair<Int, Int>? =
        if (part.isNullOrBlank()) db().queryOne("select min(page), max(page) from page") { it.int(0) to it.int(1) }
        else db().queryOne("select min(page), max(page) from page where part=?", part) { it.int(0) to it.int(1) }

    /** الصفحات في الكتاب مرتبة: قائمة مختصرة (id, part, page) للتنقل السريع */
    suspend fun pageIndex(): List<Triple<Int, String?, Int?>> = db().query("select id, part, page from page order by id") { Triple(it.int(0), it.textOrNull(1), it.intOrNull(2)) }

    private fun mapPage(r: Db.Row) = Page(r.int(0), r.textOrNull(1), r.intOrNull(2), r.intOrNull(3), r.text(4), r.textOrNull(5), r.textOrNull(6))

    /** فهرس الموضوعات كشجرة مسطَّحة بمستويات */
    suspend fun titles(): List<Title> {
        val rows = db().query("select id, page, parent, text from title order by id") { Title(it.int(0), it.int(1), it.int(2), it.text(3)) }
        val byParent = rows.groupBy { it.parent }
        val out = ArrayList<Title>(rows.size)
        fun walk(parent: Int, level: Int, seen: HashSet<Int>) {
            for (t in byParent[parent] ?: return) {
                if (!seen.add(t.id)) continue
                out.add(t.copy(level = level, hasChildren = byParent.containsKey(t.id)))
                walk(t.id, level + 1, seen)
            }
        }
        val seen = HashSet<Int>()
        walk(0, 0, seen)
        // عناوين يتيمة (أبوها غير موجود)
        for (t in rows) if (t.id !in seen) { out.add(t.copy(level = 0, hasChildren = byParent.containsKey(t.id))); seen.add(t.id); walk(t.id, 1, seen) }
        return out
    }

    /** العنوان الجاري لصفحة (آخر عنوان تبدأ صفحته قبل الصفحة أو عندها) */
    suspend fun titleForPage(pageId: Int): Title? =
        db().queryOne("select id, page, parent, text from title where page<=? order by page desc, id desc limit 1", pageId) { Title(it.int(0), it.int(1), it.int(2), it.text(3)) }

    /** مسار العنوان (الأب فالابن) */
    suspend fun titlePath(pageId: Int): List<Title> {
        val t = titleForPage(pageId) ?: return emptyList()
        val path = ArrayList<Title>(); var cur: Title? = t; var guard = 0
        while (cur != null && guard++ < 20) { path.add(0, cur); cur = if (cur.parent == 0) null else db().queryOne("select id, page, parent, text from title where id=?", cur.parent) { Title(it.int(0), it.int(1), it.int(2), it.text(3)) } }
        return path
    }

    /** بحث داخل الكتاب — يعيد معرّفات الصفحات المطابقة */
    suspend fun searchPages(fts: String, limit: Int = 500): List<Int> =
        db().query("select rowid from page_fts where page_fts match ? order by rowid limit ?", fts, limit) { it.int(0) }

    suspend fun searchPagesCount(fts: String): Int = db().count("select count(*) from page_fts where page_fts match ?", fts).toInt()

    suspend fun searchTitles(fts: String, limit: Int = 200): List<Title> {
        val ids = db().query("select rowid from title_fts where title_fts match ? limit ?", fts, limit) { it.int(0) }
        if (ids.isEmpty()) return emptyList()
        return db().query("select id, page, parent, text from title where id in (${ids.joinToString(",")}) order by page") { Title(it.int(0), it.int(1), it.int(2), it.text(3)) }
    }

    suspend fun pages(ids: List<Int>): List<Page> {
        if (ids.isEmpty()) return emptyList()
        return db().query("select id, part, page, number, body, foot, services from page where id in (${ids.joinToString(",")}) order by id") { mapPage(it) }
    }

    suspend fun meta(): Map<String, String> = db().query("select key, value from meta") { it.text(0) to it.text(1) }.toMap()
}
