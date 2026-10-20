package org.murabbie.shamela.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * التشكيل الآلي: معجم مبني من الكلمات المشكولة في نصوص المكتبة نفسها (tashkeel.db: word/bigram)،
 * يُشكِّل الكلمات غير المشكولة في أي كتاب بأرجح صورها، مع مراعاة الكلمة السابقة لحلّ الاشتباه (نحو: رسولُ اللهِ / إنّ اللهَ).
 * الكلمات المشكولة أصلًا تُترك كما هي. تشكيل معجمي لا نحوي: يُصيب في الغالب ويخطئ أحيانًا في أواخر الكلمات.
 */
class Tashkeel(private val library: Library) {

    val file: File get() = File(library.baseDir, "tashkeel.db")
    private val _state = MutableStateFlow<ServicesState>(if (file.exists() && file.length() > 0) ServicesState.Ready else ServicesState.NotInstalled)
    val state: StateFlow<ServicesState> = _state
    val installed: Boolean get() = _state.value is ServicesState.Ready

    private var db: Db? = null
    private val mutex = Mutex()
    private suspend fun db(): Db = mutex.withLock { db?.takeIf { !it.closed } ?: Db(file.path, readOnly = true).also { db = it } }
    private suspend fun closeDb() = mutex.withLock { db?.close(); db = null }

    suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        try {
            NasHttp.clearLog()
            _state.value = ServicesState.Downloading("جلب ملف الوصف…", 0, -1)
            var m = library.manifestOrFetch()
            if (m.tashkeel == null) m = library.fetchManifest()
            val mf = m.tashkeel ?: throw IllegalStateException("ملف الوصف على الخادم لا يحوي معجم التشكيل")
            val gz = File(library.tmpDir, "tashkeel.db.gz")
            NasHttp.download(mf.url, gz, mf.size) { b, t -> _state.value = ServicesState.Downloading("تنزيل معجم التشكيل", b, t) }
            if (mf.sha256.isNotBlank()) { val h = NasHttp.sha256(gz); if (!h.equals(mf.sha256, true)) { gz.delete(); throw IllegalStateException("بصمة الملف لا تطابق") } }
            _state.value = ServicesState.Downloading("تثبيت المعجم…", 0, -1)
            val tmp = File(library.tmpDir, "tashkeel.db.tmp")
            GZIPInputStream(gz.inputStream().buffered(1 shl 16)).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            closeDb()
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) { tmp.copyTo(file, true); tmp.delete() }
            gz.delete()
            cache.clear()
            _state.value = ServicesState.Ready
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.value = if (file.exists() && file.length() > 0) ServicesState.Ready else ServicesState.Error(e.message ?: "خطأ")
            false
        }
    }

    suspend fun uninstall() { closeDb(); file.delete(); cache.clear(); _state.value = ServicesState.NotInstalled }

    suspend fun counts(): Pair<Long, Long> = db().count("select count(*) from word") to db().count("select count(*) from bigram")

    // ---------- التشكيل ----------

    private val tashkeelChars = "ًٌٍَُِّْٰ"
    private val wordRegex = Regex("[\\u0621-\\u064a\\u0671$tashkeelChars]+")
    private val tagRegex = Regex("<[^>]+>")
    private fun hasTashkeel(w: String) = w.any { it in tashkeelChars }
    private fun bare(w: String) = w.filterNot { it in tashkeelChars }

    private val cache = object : LinkedHashMap<String, String?>(4096, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?): Boolean = size > 60000
    }

    /** يُشكِّل نص HTML صفحة (النص خارج الوسوم فقط) */
    suspend fun vocalizeHtml(html: String): String {
        if (!installed) return html
        // جمع الكلمات غير المشكولة وأزواجها
        val segments = ArrayList<Pair<Boolean, String>>() // (isTag, text)
        var i = 0
        for (m in tagRegex.findAll(html)) { if (m.range.first > i) segments.add(false to html.substring(i, m.range.first)); segments.add(true to m.value); i = m.range.last + 1 }
        if (i < html.length) segments.add(false to html.substring(i))
        val need = HashSet<String>(); val pairs = HashSet<Pair<String, String>>()
        var prev: String? = null
        for ((isTag, s) in segments) {
            if (isTag) continue
            for (m in wordRegex.findAll(s)) {
                val w = m.value; val b = bare(w)
                if (!hasTashkeel(w) && b.length >= 2) { need.add(b); if (prev != null) pairs.add(prev to b) }
                prev = b
            }
        }
        val uni = lookupWords(need)
        val bi = lookupBigrams(pairs)
        val sb = StringBuilder(html.length + html.length / 3)
        prev = null
        for ((isTag, s) in segments) {
            if (isTag) { sb.append(s); continue }
            var last = 0
            for (m in wordRegex.findAll(s)) {
                sb.append(s, last, m.range.first)
                val w = m.value; val b = bare(w)
                val v = if (!hasTashkeel(w) && b.length >= 2) (prev?.let { bi[it to b] } ?: uni[b]) else null
                sb.append(v ?: w)
                prev = b; last = m.range.last + 1
            }
            sb.append(s, last, s.length)
        }
        return sb.toString()
    }

    /** يُشكِّل نصًا عاديًا (للقراءة الصوتية مثلًا) */
    suspend fun vocalizeText(text: String): String = vocalizeHtml(text)

    private suspend fun lookupWords(bares: Set<String>): Map<String, String> {
        val out = HashMap<String, String>()
        val missing = ArrayList<String>()
        synchronized(cache) { for (b in bares) { if (cache.containsKey(b)) cache[b]?.let { out[b] = it } else missing.add(b) } }
        for (chunk in missing.chunked(400)) {
            val found = db().query("select bare, voc from word where bare in (${chunk.joinToString(",") { "?" }})", *chunk.toTypedArray()) { it.text(0) to it.text(1) }.toMap()
            synchronized(cache) { for (b in chunk) { val v = found[b]; cache[b] = v; if (v != null) out[b] = v } }
        }
        return out
    }

    private suspend fun lookupBigrams(pairs: Set<Pair<String, String>>): Map<Pair<String, String>, String> {
        if (pairs.isEmpty()) return emptyMap()
        val out = HashMap<Pair<String, String>, String>()
        val byBare = pairs.groupBy({ it.second }, { it.first })
        for (chunk in byBare.keys.chunked(300)) {
            val rows = db().query("select prev, bare, voc from bigram where bare in (${chunk.joinToString(",") { "?" }})", *chunk.toTypedArray()) { Triple(it.text(0), it.text(1), it.text(2)) }
            for ((p, b, v) in rows) if (byBare[b]?.contains(p) == true) out[p to b] = v
        }
        return out
    }
}
