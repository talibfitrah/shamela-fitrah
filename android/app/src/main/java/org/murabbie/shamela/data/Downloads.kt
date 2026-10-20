package org.murabbie.shamela.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

enum class JobStatus { QUEUED, DOWNLOADING, INSTALLING, DONE, ERROR, PAUSED }
enum class JobSource { NAS, WEB }

data class DownloadJob(
    val bookId: Int,
    val bookName: String,
    val source: JobSource,
    val status: JobStatus,
    val bytes: Long = 0,
    val total: Long = -1,
    val message: String = "",
    val order: Long = 0,
)

/** إحصاء إجمالي للطابور (لعرضه دون الاحتفاظ بآلاف المهام في الواجهة). */
data class BulkStats(val queued: Int = 0, val active: Int = 0, val done: Int = 0, val error: Int = 0, val paused: Int = 0) {
    val pendingTotal: Int get() = queued + active
}

/**
 * مدير تنزيل الكتب بنظام «طابور + عمّال»: يتحمّل تنزيل المكتبة كلها (٨٥٩١ كتابًا) دفعة واحدة دون
 * إنشاء آلاف الكوروتينات. عدد محدود من العمّال يسحب من الطابور؛ التنزيل من حزم NAS بطلب مدى (Range)
 * مع التحقق من البصمة وفك الضغط، أو من موقع shamela.ws للكتب التي لم تصل NAS بعد.
 * تُعرض في الواجهة المهام النشطة والمنتهية بخطأ فقط؛ أما الطابور الكبير فيُعرض عدده.
 */
class Downloads(private val context: Context, private val library: Library, private val settings: Settings) {

    companion object { const val WORKERS = 2 }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // المهام المعروضة في الواجهة (نشطة أو منتهية بخطأ أو انتهت حديثًا) — صغيرة الحجم
    private val _jobs = MutableStateFlow<Map<Int, DownloadJob>>(LinkedHashMap())
    val jobs: StateFlow<Map<Int, DownloadJob>> = _jobs

    // معرّفات في الانتظار (طابور) — لعرض حالة «في الانتظار» على بطاقات الكتب
    private val _queued = MutableStateFlow<Set<Int>>(emptySet())
    val queued: StateFlow<Set<Int>> = _queued

    private val _bulk = MutableStateFlow(BulkStats())
    val bulk: StateFlow<BulkStats> = _bulk

    private val pending = ArrayDeque<Int>()                 // طابور المعرّفات
    private val pendingSet = HashSet<Int>()
    private val webBooks = ConcurrentHashMap<Int, Book>()   // كتب من الموقع (ليست في الفهرس)
    private val lock = Any()
    private val workers = ArrayList<Job>()
    private var seq = 0L
    private var doneCount = 0
    private var errorCount = 0

    @Volatile var paused = false; private set

    var onActivity: (Boolean) -> Unit = {}
    var onWebImported: suspend (Int, File) -> Unit = { _, _ -> }
    /** النسخ الاحتياطي الخارجي: يُفحص قبل أي تنزيل، ويُنسخ إليه كل كتاب يُثبَّت */
    @Volatile var backup: Backup? = null

    /** تثبيت كتاب: من المجلد الاحتياطي إن وُجد فيه (بلا شبكة)، وإلا من الخادم أو الموقع ثم نسخه احتياطيًّا. */
    private suspend fun installBook(book: Book) {
        val b = backup
        if (b != null && runCatching { b.restoreBook(book.id) }.getOrDefault(false)) {
            updateJob(book.id) { it.copy(status = JobStatus.INSTALLING, message = "استُعيد من النسخة الاحتياطية على الهاتف") }
            return
        }
        if (book.file != null && book.file.pack.isNotBlank()) downloadFromNas(book) else downloadFromWeb(book)
        if (b != null) scope.launch { runCatching { b.mirrorBook(book.id) } }
    }

    fun jobFor(id: Int): DownloadJob? = _jobs.value[id]

    fun isActive(): Boolean = synchronized(lock) { pending.isNotEmpty() || _jobs.value.values.any { it.status == JobStatus.DOWNLOADING || it.status == JobStatus.INSTALLING } }

    private fun putJob(job: DownloadJob) { _jobs.value = LinkedHashMap(_jobs.value).apply { put(job.bookId, job) } }
    private fun updateJob(id: Int, f: (DownloadJob) -> DownloadJob) { val cur = _jobs.value[id] ?: return; putJob(f(cur)) }
    private fun removeJob(id: Int) { _jobs.value = LinkedHashMap(_jobs.value).apply { remove(id) } }

    private fun refreshBulk() {
        val active = _jobs.value.values.count { it.status == JobStatus.DOWNLOADING || it.status == JobStatus.INSTALLING }
        val paused = _jobs.value.values.count { it.status == JobStatus.PAUSED }
        _bulk.value = BulkStats(queued = synchronized(lock) { pending.size }, active = active, done = doneCount, error = errorCount, paused = paused)
    }

    fun enqueue(book: Book, source: JobSource = if (book.file != null && book.file.pack.isNotBlank()) JobSource.NAS else JobSource.WEB) {
        if (library.isInstalled(book.id)) return
        synchronized(lock) {
            if (book.id in pendingSet) return
            val j = _jobs.value[book.id]
            if (j != null && (j.status == JobStatus.DOWNLOADING || j.status == JobStatus.INSTALLING)) return
            if (source == JobSource.WEB) webBooks[book.id] = book
            pending.addLast(book.id); pendingSet.add(book.id)
        }
        putJob(DownloadJob(book.id, book.name, source, JobStatus.QUEUED, order = seq++))
        _queued.value = _queued.value + book.id
        paused = false
        ensureWorkers()
        refreshBulk()
    }

    /** إضافة قائمة كبيرة (قسم كامل أو المكتبة كلها) دفعةً واحدة دون إغراق الواجهة. */
    fun enqueueAll(books: List<Book>) {
        val added = ArrayList<Int>(books.size)
        synchronized(lock) {
            for (b in books) {
                if (library.isInstalled(b.id) || b.id in pendingSet) continue
                val j = _jobs.value[b.id]
                if (j != null && (j.status == JobStatus.DOWNLOADING || j.status == JobStatus.INSTALLING)) continue
                if (b.file == null || b.file.pack.isBlank()) webBooks[b.id] = b
                pending.addLast(b.id); pendingSet.add(b.id); added.add(b.id)
            }
        }
        if (added.isEmpty()) return
        _queued.value = _queued.value + added
        paused = false
        ensureWorkers()
        refreshBulk()
    }

    private fun nextId(): Int? = synchronized(lock) { if (pending.isEmpty()) null else pending.pollFirst().also { pendingSet.remove(it) } }

    private fun ensureWorkers() {
        synchronized(lock) {
            workers.removeAll { !it.isActive }
            while (workers.size < WORKERS) workers.add(scope.launch { workerLoop() })
        }
    }

    private suspend fun workerLoop() {
        while (!paused) {
            val id = nextId() ?: break
            _queued.value = _queued.value - id
            refreshBulk()
            // محفوظ في مجلد الحفظ؟ استعادة فورية بلا شبكة قبل انتظارها
            val early = webBooks[id] ?: library.book(id)
            if (early != null && backup?.let { runCatching { it.restoreBook(id) }.getOrDefault(false) } == true) {
                if (_jobs.value[id] == null) putJob(DownloadJob(id, early.name, JobSource.NAS, JobStatus.DOWNLOADING, order = seq++))
                doneCount++
                updateJob(id) { it.copy(status = JobStatus.DONE, message = "استُعيد من النسخة الاحتياطية على الهاتف") }
                library.bumpInstalled(); webBooks.remove(id); pruneDone(); refreshBulk()
                continue
            }
            waitForNetwork(id)
            if (paused) { markPaused(id); break }
            onActivity(true)
            val book = webBooks[id] ?: library.book(id)
            if (book == null) { errorCount++; updateJob(id) { it.copy(status = JobStatus.ERROR, message = "الكتاب غير موجود في الفهرس") }; refreshBulk(); continue }
            try {
                if (_jobs.value[id] == null) putJob(DownloadJob(id, book.name, if (webBooks.containsKey(id)) JobSource.WEB else JobSource.NAS, JobStatus.DOWNLOADING, order = seq++))
                installBook(book)
                doneCount++
                updateJob(id) { it.copy(status = JobStatus.DONE, message = "") }
                library.bumpInstalled()
                webBooks.remove(id)
                // إزالة المهام المكتملة القديمة من الواجهة لإبقائها خفيفة (نُبقي آخر ٣٠)
                pruneDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                markPaused(id); break
            } catch (e: Exception) {
                errorCount++
                updateJob(id) { it.copy(status = JobStatus.ERROR, message = e.message ?: "خطأ") }
            }
            refreshBulk()
        }
        onActivity(isActive())
        refreshBulk()
    }

    private fun markPaused(id: Int) {
        // أعِد المعرّف إلى الطابور ليُستأنف لاحقًا
        synchronized(lock) { if (id !in pendingSet) { pending.addFirst(id); pendingSet.add(id) } }
        _queued.value = _queued.value + id
        updateJob(id) { it.copy(status = JobStatus.PAUSED) }
    }

    private fun pruneDone() {
        val done = _jobs.value.values.filter { it.status == JobStatus.DONE }.sortedBy { it.order }
        if (done.size > 30) {
            val drop = done.take(done.size - 30).map { it.bookId }.toSet()
            _jobs.value = LinkedHashMap(_jobs.value).apply { keys.removeAll(drop) }
        }
    }

    private suspend fun waitForNetwork(id: Int) {
        while (!paused) {
            if (networkOk()) return
            updateJob(id) { it.copy(status = JobStatus.QUEUED, message = if (settings.value.wifiOnlyDownloads) "بانتظار Wi-Fi" else "بانتظار الشبكة") }
            delay(3000)
        }
    }

    private fun networkOk(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (settings.value.wifiOnlyDownloads) return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return true
    }

    private suspend fun downloadFromNas(book: Book) = withContext(Dispatchers.IO) {
        val id = book.id
        val f = book.file ?: throw IllegalStateException("لا ملف لهذا الكتاب على الخادم")
        val manifest = library.manifestOrFetch()
        val pack = manifest.packs[f.pack] ?: throw IllegalStateException("الحزمة ${f.pack} غير معرَّفة في ملف الوصف")
        val gz = File(library.tmpDir, "$id.db.gz")
        // تحقق من المساحة قبل التنزيل (المضغوط + المفكوك مؤقتًا)
        val need = f.length + f.size + (4L shl 20)
        val free = library.freeSpace()
        if (free in 0 until need) throw IllegalStateException("المساحة الحرة لا تكفي (${ArabicText.formatSize(free)})")
        updateJob(id) { it.copy(status = JobStatus.DOWNLOADING, bytes = 0, total = f.length, message = "") }
        val onProg: suspend (Long, Long) -> Unit = { b, t -> updateJob(id) { it.copy(status = JobStatus.DOWNLOADING, bytes = b, total = t) } }
        NasHttp.downloadRange(pack.url, gz, f.offset, f.length, onProgress = onProg)
        updateJob(id) { it.copy(status = JobStatus.INSTALLING, message = "التحقق من الملف…") }
        val h = NasHttp.sha256(gz)
        if (f.sha256.isNotBlank() && !h.equals(f.sha256, true)) { gz.delete(); throw IllegalStateException("بصمة الملف لا تطابق؛ أعد المحاولة") }
        installGz(id, gz, f.size)
    }

    private fun installGz(id: Int, gz: File, expectedSize: Long) {
        updateJob(id) { it.copy(status = JobStatus.INSTALLING, message = "فك الضغط…") }
        val tmp = File(library.tmpDir, "$id.db.tmp")
        GZIPInputStream(gz.inputStream().buffered(1 shl 16)).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        if (expectedSize > 0 && tmp.length() != expectedSize) { tmp.delete(); throw IllegalStateException("حجم الملف بعد فك الضغط لا يطابق") }
        val dest = library.bookFile(id)
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
        gz.delete()
    }

    private suspend fun downloadFromWeb(book: Book) {
        val id = book.id
        val dest = library.bookFile(id)
        val tmp = File(library.tmpDir, "$id.web.db")
        updateJob(id) { it.copy(status = JobStatus.DOWNLOADING, bytes = 0, total = -1, message = "جلب الكتاب من shamela.ws") }
        ShamelaWeb.importBook(id, tmp) { done, total, msg -> updateJob(id) { it.copy(status = JobStatus.DOWNLOADING, bytes = done.toLong(), total = total.toLong(), message = msg) } }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
        onWebImported(id, dest)
    }

    fun cancel(id: Int) {
        synchronized(lock) { pending.remove(id); pendingSet.remove(id) }
        webBooks.remove(id)
        _queued.value = _queued.value - id
        removeJob(id)
        File(library.tmpDir, "$id.db.gz").delete()
        File(library.tmpDir, "$id.web.db").delete()
        refreshBulk()
        onActivity(isActive())
    }

    fun retry(book: Book) {
        errorCount = (errorCount - 1).coerceAtLeast(0)
        removeJob(book.id)
        enqueue(book)
    }

    /**
     * تنزيل كتاب فورًا (للقراءة عند الطلب) خارج طابور العمّال ليكون سريع الاستجابة، مع تسجيل مهمة
     * تظهر في الواجهة. يعيد true عند الجاهزية. آمن للاستدعاء المتكرر (يتخطى إن كان مثبَّتًا أو جاريًا).
     */
    suspend fun ensureInstalled(book: Book): Boolean {
        if (library.isInstalled(book.id)) return true
        NasHttp.clearLog()
        val existing = _jobs.value[book.id]
        val nas = book.file != null && book.file.pack.isNotBlank()
        if (!nas) webBooks[book.id] = book
        // أزِله من الطابور إن كان فيه (سنُنزّله الآن مباشرة)
        synchronized(lock) { if (pending.remove(book.id)) pendingSet.remove(book.id) }
        _queued.value = _queued.value - book.id
        if (existing?.status == JobStatus.DOWNLOADING || existing?.status == JobStatus.INSTALLING) {
            // جارٍ فعلًا: انتظر حتى ينتهي
            while (!library.isInstalled(book.id)) {
                val j = _jobs.value[book.id] ?: break
                if (j.status == JobStatus.ERROR) return false
                delay(400)
            }
            return library.isInstalled(book.id)
        }
        putJob(DownloadJob(book.id, book.name, if (nas) JobSource.NAS else JobSource.WEB, JobStatus.DOWNLOADING, order = seq++))
        onActivity(true)
        return try {
            installBook(book)
            doneCount++; updateJob(book.id) { it.copy(status = JobStatus.DONE, message = "") }
            library.bumpInstalled(); pruneDone(); refreshBulk(); true
        } catch (e: kotlinx.coroutines.CancellationException) {
            updateJob(book.id) { it.copy(status = JobStatus.PAUSED) }; throw e
        } catch (e: Exception) {
            errorCount++; updateJob(book.id) { it.copy(status = JobStatus.ERROR, message = e.message ?: "خطأ") }; refreshBulk(); false
        } finally { onActivity(isActive()) }
    }

    fun pauseAll() {
        paused = true
        synchronized(lock) { workers.forEach { it.cancel() }; workers.clear() }
        _jobs.value = LinkedHashMap(_jobs.value).mapValues { (_, j) -> if (j.status == JobStatus.DOWNLOADING || j.status == JobStatus.INSTALLING) j.copy(status = JobStatus.PAUSED) else j }
        refreshBulk()
        onActivity(false)
    }

    fun resumeAll() {
        paused = false
        // أعِد كل ما في الواجهة بحالة PAUSED/ERROR إلى الطابور
        val toResume = _jobs.value.values.filter { it.status == JobStatus.PAUSED || it.status == JobStatus.ERROR }.map { it.bookId }
        synchronized(lock) {
            for (id in toResume) if (id !in pendingSet) { pending.addLast(id); pendingSet.add(id) }
        }
        if (toResume.isNotEmpty()) _queued.value = _queued.value + toResume
        _jobs.value = LinkedHashMap(_jobs.value).mapValues { (_, j) -> if (j.status == JobStatus.PAUSED || j.status == JobStatus.ERROR) j.copy(status = JobStatus.QUEUED, message = "") else j }
        errorCount = 0
        ensureWorkers()
        refreshBulk()
    }

    fun clearFinished() {
        _jobs.value = LinkedHashMap(_jobs.value).filterValues { it.status != JobStatus.DONE }
        doneCount = 0
        refreshBulk()
    }

    /** نص وتقدّم لعرضهما في الإشعار. */
    fun summary(): Pair<String, Float> {
        val active = _jobs.value.values.filter { it.status == JobStatus.DOWNLOADING || it.status == JobStatus.INSTALLING }.sortedBy { it.order }
        val q = _bulk.value.queued
        val cur = active.firstOrNull() ?: return ("بانتظار… " + (if (q > 0) "(${ArabicText.arabicDigits(q)} في الطابور)" else "")) to -1f
        val p = if (cur.total > 0) cur.bytes.toFloat() / cur.total else -1f
        val text = buildString {
            append(cur.bookName)
            if (cur.status == JobStatus.INSTALLING) append(" — ").append(cur.message)
            else if (cur.total > 0) append(" — ").append(ArabicText.formatSize(cur.bytes)).append(" / ").append(ArabicText.formatSize(cur.total))
            else if (cur.message.isNotBlank()) append(" — ").append(cur.message)
            val remaining = q + active.size - 1
            if (remaining > 0) append("  (باقٍ ").append(ArabicText.arabicDigits(remaining)).append(")")
            if (doneCount > 0) append(" • تمّ ").append(ArabicText.arabicDigits(doneCount))
        }
        return text to p
    }
}
