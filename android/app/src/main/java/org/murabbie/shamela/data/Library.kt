package org.murabbie.shamela.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream

sealed class LibraryState {
    object Checking : LibraryState()
    object NotInstalled : LibraryState()
    data class Downloading(val message: String, val bytes: Long, val total: Long) : LibraryState()
    data class Ready(val dataVersion: String) : LibraryState()
    data class Error(val message: String) : LibraryState()
}

/**
 * الفهرس الرئيس للمكتبة (master.db المنزَّل من NAS): الأقسام والمؤلفون والكتب ومواضع ملفات الكتب في الحزم.
 * يتولى أيضًا جلب manifest.json وتحديث الفهرس.
 */
class Library(private val context: Context, val settings: Settings) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val baseDir: File get() {
        val ext = if (settings.value.storageExternal) context.getExternalFilesDir(null) else null
        return File(ext ?: context.filesDir, "shamela").apply { mkdirs() }
    }
    val masterFile: File get() = File(baseDir, "master.db")
    val booksDir: File get() = File(baseDir, "books").apply { mkdirs() }
    val tmpDir: File get() = File(baseDir, "tmp").apply { mkdirs() }
    private val manifestCache: File get() = File(baseDir, "manifest.json")

    private val _state = MutableStateFlow<LibraryState>(LibraryState.Checking)
    val state: StateFlow<LibraryState> = _state

    private val _manifest = MutableStateFlow<Manifest?>(null)
    val manifest: StateFlow<Manifest?> = _manifest

    /** يتغير عند تثبيت/حذف كتاب لتحديث الواجهات */
    private val _installedVersion = MutableStateFlow(0)
    val installedVersion: StateFlow<Int> = _installedVersion
    fun bumpInstalled() { _installedVersion.value = _installedVersion.value + 1 }

    private var master: Db? = null
    private val masterMutex = Mutex()

    /** ملف الوصف المضمَّن في التطبيق (يُستعمل حين لا يوجد ملف محدَّث من الخادم). */
    private fun bundledManifest(): Manifest? = runCatching { context.assets.open("manifest.json").bufferedReader().use { Manifest.parse(it.readText()) } }.getOrNull()

    init {
        // الأحدث من: ملف الوصف المخزون من الخادم، أو المضمَّن في التطبيق (قد يكون التطبيق أحدث من آخر جلب)
        val cached = runCatching { if (manifestCache.exists()) Manifest.parse(manifestCache.readText()) else null }.getOrNull()
        val bundled = bundledManifest()
        _manifest.value = when {
            cached == null -> bundled
            bundled == null -> cached
            bundled.generated > cached.generated -> bundled
            else -> cached
        }
        scope.launch { check() }
    }

    suspend fun check() {
        if (masterFile.exists() && masterFile.length() > 0) {
            val ok = runCatching { db().count("select count(*) from book") > 0 }.getOrDefault(false)
            if (ok) { _state.value = LibraryState.Ready(settings.value.dataVersion); return }
        }
        // لا فهرس مثبَّتًا: ثبِّت الفهرس المضمَّن في التطبيق فورًا (بلا شبكة)، ثم يُحدَّث من الخادم لاحقًا عند توفره.
        if (installBundledMaster()) return
        _state.value = LibraryState.NotInstalled
    }

    /** تثبيت master.db.gz المضمَّن في حزمة التطبيق. */
    private suspend fun installBundledMaster(): Boolean = withContext(Dispatchers.IO) {
        try {
            _state.value = LibraryState.Downloading("تثبيت فهرس المكتبة…", 0, -1)
            val tmp = File(tmpDir, "master.db.tmp")
            context.assets.open("master.gzdb").use { a ->
                GZIPInputStream(a.buffered(1 shl 16)).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            }
            closeMaster()
            if (!tmp.renameTo(masterFile)) { tmp.copyTo(masterFile, true); tmp.delete() }
            val ok = runCatching { db().count("select count(*) from book") > 0 }.getOrDefault(false)
            if (!ok) { closeMaster(); masterFile.delete(); return@withContext false }
            val v = bundledManifest()?.dataVersion ?: ""
            settings.update { it.copy(dataVersion = v) }
            _state.value = LibraryState.Ready(v)
            bumpInstalled()
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    suspend fun db(): Db = masterMutex.withLock {
        master?.takeIf { !it.closed } ?: Db(masterFile.path, readOnly = true).also { master = it }
    }

    private suspend fun closeMaster() = masterMutex.withLock { master?.close(); master = null }

    // ---------- manifest ----------

    suspend fun fetchManifest(force: Boolean = false): Manifest {
        NasHttp.setCustomBase(settings.value.nasBase.takeUnless { org.murabbie.shamela.BuildConfig.PLAY_STORE && !it.startsWith("https://") })
        val m = Manifest.parse(NasHttp.fetchText(settings.value.manifestUrl))
        _manifest.value = m
        runCatching { manifestCache.writeText(m.raw) }
        settings.update { it.copy(lastManifestCheck = System.currentTimeMillis()) }
        return m
    }

    suspend fun manifestOrFetch(): Manifest = _manifest.value ?: fetchManifest()

    // ---------- تثبيت الفهرس ----------

    /** تنزيل master.db.gz من NAS وتثبيته (يُستعمل لأول مرة ولتحديث الفهرس). */
    suspend fun installMaster(): Boolean = withContext(Dispatchers.IO) {
        try {
            NasHttp.clearLog()
            NasHttp.note("الإصدار ${org.murabbie.shamela.BuildConfig.VERSION_NAME} — ${settings.value.manifestUrl.take(60)}")
            _state.value = LibraryState.Downloading("جلب ملف الوصف…", 0, -1)
            val m = fetchManifest()
            val mf = m.master ?: throw IllegalStateException("ملف الوصف لا يحوي master")
            val gz = File(tmpDir, "master.db.gz")
            NasHttp.download(mf.url, gz, mf.size) { b, t -> _state.value = LibraryState.Downloading("تنزيل فهرس المكتبة", b, t) }
            if (mf.sha256.isNotBlank()) {
                val h = NasHttp.sha256(gz)
                if (!h.equals(mf.sha256, true)) { gz.delete(); throw IllegalStateException("بصمة الفهرس لا تطابق") }
            }
            _state.value = LibraryState.Downloading("تثبيت الفهرس…", 0, -1)
            val tmp = File(tmpDir, "master.db.tmp")
            GZIPInputStream(gz.inputStream().buffered(1 shl 16)).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            closeMaster()
            if (!tmp.renameTo(masterFile)) { tmp.copyTo(masterFile, true); tmp.delete() }
            gz.delete()
            settings.update { it.copy(dataVersion = m.dataVersion) }
            _state.value = LibraryState.Ready(m.dataVersion)
            bumpInstalled()
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.value = if (masterFile.exists()) LibraryState.Ready(settings.value.dataVersion) else LibraryState.Error(e.message ?: "خطأ")
            false
        }
    }

    /** هل في manifest إصدار بيانات أحدث من المثبَّت؟ */
    fun dataUpdateAvailable(): Boolean {
        val m = _manifest.value ?: return false
        return m.dataVersion.isNotBlank() && m.dataVersion != settings.value.dataVersion && masterFile.exists()
    }

    // ---------- الاستعلامات ----------

    suspend fun categories(): List<Category> = db().query(
        "select c.category_id, c.category_name, c.category_order, (select count(*) from book b where b.book_category=c.category_id and b.hidden=0) from category c order by c.category_order"
    ) { Category(it.int(0), it.text(1), it.int(2), it.int(3)) }

    suspend fun category(id: Int): Category? = db().queryOne("select category_id, category_name, category_order from category where category_id=?", id) { Category(it.int(0), it.text(1), it.int(2)) }

    suspend fun authors(query: String = "", limit: Int = 4000): List<Author> {
        val q = ArabicText.normalize(query).trim()
        return if (q.isEmpty()) db().query(
            "select a.author_id, a.author_name, a.death_number, a.death_text, (select count(*) from author_book ab where ab.author_id=a.author_id) from author a order by a.death_number, a.author_name limit ?", limit
        ) { Author(it.int(0), it.text(1), it.int(2), it.textOrNull(3), it.int(4)) }
        else db().query(
            "select a.author_id, a.author_name, a.death_number, a.death_text, (select count(*) from author_book ab where ab.author_id=a.author_id) from author a join author_norm n on n.author_id=a.author_id where n.name_norm like ? order by a.death_number, a.author_name limit ?",
            "%$q%", limit
        ) { Author(it.int(0), it.text(1), it.int(2), it.textOrNull(3), it.int(4)) }
    }

    suspend fun author(id: Int): Author? = db().queryOne("select author_id, author_name, death_number, death_text from author where author_id=?", id) { Author(it.int(0), it.text(1), it.int(2), it.textOrNull(3)) }

    private val bookCols = """b.book_id, b.book_name, b.book_category, b.book_type, b.book_date, b.authors, b.main_author,
        coalesce(a.author_name,''), b.printed, b.group_id, b.pdf_links, b.meta_data,
        f.pack, f.offset, f.length, f.size, f.sha256, f.pages, f.titles, coalesce(c.category_name,'')"""
    private val bookFrom = "from book b left join author a on a.author_id=b.main_author left join book_file f on f.book_id=b.book_id left join category c on c.category_id=b.book_category"

    private fun mapBook(r: Db.Row): Book {
        val file = if (r.isNull(12)) null else BookFile(r.text(12), r.long(13), r.long(14), r.long(15), r.text(16), r.int(17), r.int(18))
        val authors = r.text(5).split(',', ' ').mapNotNull { it.trim().toIntOrNull() }
        return Book(r.int(0), r.text(1), r.int(2), r.int(3), r.int(4), authors, r.int(6), r.text(7), r.int(8), r.intOrNull(9), r.textOrNull(10), r.textOrNull(11), file, r.text(19))
    }

    suspend fun book(id: Int): Book? = db().queryOne("select $bookCols $bookFrom where b.book_id=?", id, mapper = ::mapBook)

    suspend fun booksByIds(ids: Collection<Int>): List<Book> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<Book>()
        ids.chunked(500).forEach { chunk ->
            out += db().query("select $bookCols $bookFrom where b.book_id in (${chunk.joinToString(",")}) and b.hidden=0 order by b.book_name", mapper = ::mapBook)
        }
        return out
    }

    suspend fun booksInCategory(categoryId: Int): List<Book> =
        db().query("select $bookCols $bookFrom where b.book_category=? and b.hidden=0 order by b.book_name", categoryId, mapper = ::mapBook)

    suspend fun booksByAuthor(authorId: Int): List<Book> =
        db().query("select $bookCols $bookFrom where b.hidden=0 and b.book_id in (select book_id from author_book where author_id=? union select book_id from coauthor_book where author_id=?) order by b.book_name", authorId, authorId, mapper = ::mapBook)

    suspend fun booksInGroup(groupId: Int): List<Book> =
        db().query("select $bookCols $bookFrom where b.group_id=? and b.hidden=0 order by b.book_name", groupId, mapper = ::mapBook)

    /** بحث في أسماء الكتب والمؤلفين (مطبَّع). */
    suspend fun searchBooks(query: String, limit: Int = 300): List<Book> {
        val words = ArabicText.queryWords(query).map { it.trimEnd('*') }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val where = words.joinToString(" and ") { "(n.name_norm like ? or n.author_norm like ?)" }
        val args = ArrayList<Any?>()
        for (w in words) { args.add("%$w%"); args.add("%$w%") }
        args.add(limit)
        return db().query("select $bookCols $bookFrom join book_norm n on n.book_id=b.book_id where b.hidden=0 and $where order by b.book_name limit ?", *args.toTypedArray(), mapper = ::mapBook)
    }

    /** كتب تحوي أسماؤها أحد الأنماط (مطبَّعة)، مع استبعاد كتب مؤلف معيَّن — للشروح والدراسات حول عَلَم. */
    suspend fun booksNamedLike(patterns: List<String>, excludeAuthor: Int): List<Book> {
        val pats = patterns.map { ArabicText.normalize(it).trim() }.filter { it.isNotEmpty() }
        if (pats.isEmpty()) return emptyList()
        val where = pats.joinToString(" or ") { "n.name_norm like ?" }
        val args = ArrayList<Any?>(); pats.forEach { args.add("%$it%") }; args.add(excludeAuthor); args.add(excludeAuthor)
        return db().query("select $bookCols $bookFrom join book_norm n on n.book_id=b.book_id where b.hidden=0 and ($where) and b.book_id not in (select book_id from author_book where author_id=? union select book_id from coauthor_book where author_id=?) order by b.book_name", *args.toTypedArray(), mapper = ::mapBook)
    }

    /** آخر الكتب المضافة إلى الشاملة (بحسب تاريخ الإضافة في meta) */
    suspend fun recentBooks(limit: Int = 200): List<Book> =
        db().query("select $bookCols $bookFrom join book_norm n on n.book_id=b.book_id where b.hidden=0 order by n.added_key desc, b.book_id desc limit ?", limit, mapper = ::mapBook)

    suspend fun allBooks(): List<Book> = db().query("select $bookCols $bookFrom where b.hidden=0 order by b.book_name", mapper = ::mapBook)

    suspend fun bookIdsInCategory(categoryId: Int): List<Int> = db().query("select book_id from book where book_category=? and hidden=0", categoryId) { it.int(0) }
    suspend fun allBookIds(): List<Int> = db().query("select book_id from book where hidden=0") { it.int(0) }
    /** كل المعرِّفات في الفهرس (حتى المخفية) — لمقارنة الموقع */
    suspend fun indexedIds(): Set<Int> = db().query("select book_id from book") { it.int(0) }.toHashSet()

    suspend fun stats(): Map<String, Long> {
        val d = db()
        return mapOf(
            "books" to d.count("select count(*) from book where hidden=0"),
            "authors" to d.count("select count(*) from author"),
            "categories" to d.count("select count(*) from category"),
            "pages" to d.count("select coalesce(sum(pages),0) from book_file"),
            "size" to d.count("select coalesce(sum(size),0) from book_file"),
            "download" to d.count("select coalesce(sum(length),0) from book_file"),
        )
    }

    // ---------- ملفات الكتب المثبَّتة ----------

    fun bookFile(id: Int): File = File(booksDir, "$id.db")
    fun isInstalled(id: Int): Boolean = bookFile(id).let { it.exists() && it.length() > 0 }

    fun installedIds(): Set<Int> = booksDir.listFiles { f -> f.name.endsWith(".db") }?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }?.toSet() ?: emptySet()

    fun installedSize(): Long = booksDir.listFiles()?.sumOf { it.length() } ?: 0L

    suspend fun deleteBook(id: Int) {
        BookStore.close(id)
        bookFile(id).delete()
        bumpInstalled()
    }

    suspend fun deleteAllBooks() {
        BookStore.closeAll()
        booksDir.listFiles()?.forEach { it.delete() }
        bumpInstalled()
    }

    /** المساحة الحرة في مجلد التخزين */
    fun freeSpace(): Long = runCatching { baseDir.usableSpace }.getOrDefault(-1L)
}
