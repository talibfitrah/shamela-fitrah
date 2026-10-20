package org.murabbie.shamela.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * النسخ الاحتياطي الخارجي للكتب واستعادتها:
 * المشكلة: كتب التطبيق تُحفظ في مخزنه الخاص، والنظام يمحوه كله عند إزالة التطبيق، فيفقد المستخدم كتبه.
 * الحل: مجلد يختاره المستخدم في ذاكرة الهاتف العامة (مثل Documents/Shamela) عبر SAF — هذا المجلد يبقى بعد
 * إزالة التطبيق. كل كتاب يُثبَّت يُنسخ إليه مضغوطًا (<id>.db.gz + <id>.json للبيانات الوصفية للكتب غير الفهرسية)،
 * وبعد إعادة التثبيت يختار المستخدم المجلد نفسه فتُستعاد كل الكتب دون تنزيل. والتطبيق يفحص المجلد قبل أي
 * تنزيل: إن وجد الكتاب فيه استعاده منه فورًا بلا شبكة.
 */
class Backup(private val context: Context, private val library: Library, private val settings: Settings, private val userDb: UserDb) {

    /** معرِّفات الكتب الموجودة في مجلد الحفظ — لعرضها في قوائم المكتبة بوصفها «محفوظة على الهاتف» */
    private val _backedIds = MutableStateFlow<Set<Int>>(emptySet())
    val backedIds: StateFlow<Set<Int>> = _backedIds

    val uri: Uri? get() = settings.value.backupUri.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** هل المجلد مضبوط والإذن عليه لا يزال قائمًا؟ */
    fun available(): Boolean {
        val u = uri ?: return false
        val held = context.contentResolver.persistedUriPermissions.any { it.uri == u && it.isReadPermission && it.isWritePermission }
        return held && (DocumentFile.fromTreeUri(context, u)?.canWrite() == true)
    }

    fun folderName(): String = uri?.let { DocumentFile.fromTreeUri(context, it)?.name } ?: ""

    fun setFolder(u: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        settings.update { it.copy(backupUri = u.toString()) }
        synchronized(this) { cache = null }
    }

    fun clearFolder() { settings.update { it.copy(backupUri = "") }; synchronized(this) { cache = null }; _backedIds.value = emptySet() }

    private fun dir(): DocumentFile? = uri?.let { DocumentFile.fromTreeUri(context, it) }?.takeIf { it.isDirectory }

    // فهرس ملفات المجلد (اسم → ملف) — يُحدَّث عند الحاجة لأن findFile بطيء
    private var cache: HashMap<String, DocumentFile>? = null
    private fun index(refresh: Boolean = false): HashMap<String, DocumentFile> = synchronized(this) {
        var c = cache
        if (c == null || refresh) {
            c = HashMap()
            runCatching { dir()?.listFiles()?.forEach { f -> f.name?.let { c!![it] = f } } }
            cache = c
        }
        c!!
    }

    /** الكتب الموجودة في المجلد الاحتياطي: معرِّف الكتاب → حجم الملف المضغوط */
    fun listBackedUp(refresh: Boolean = true): Map<Int, Long> {
        val m = index(refresh).mapNotNull { (name, f) -> name.removeSuffix(".db.gz").toIntOrNull()?.takeIf { name.endsWith(".db.gz") }?.let { it to f.length() } }.toMap()
        _backedIds.value = m.keys
        return m
    }

    /** نسخ كتاب مثبَّت إلى المجلد (مضغوطًا)، مع بيانات الكتب غير الفهرسية. */
    suspend fun mirrorBook(id: Int): Boolean = withContext(Dispatchers.IO) {
        if (!available()) return@withContext false
        val src = library.bookFile(id)
        if (!src.exists() || src.length() == 0L) return@withContext false
        val d = dir() ?: return@withContext false
        try {
            val name = "$id.db.gz"
            index().remove(name)?.delete()
            val out = d.createFile("application/gzip", name) ?: return@withContext false
            context.contentResolver.openOutputStream(out.uri, "w")?.use { os ->
                GZIPOutputStream(os.buffered(1 shl 16)).use { gz -> src.inputStream().buffered(1 shl 16).use { it.copyTo(gz, 1 shl 16) } }
            } ?: return@withContext false
            synchronized(this@Backup) { cache?.put(name, out) }
            _backedIds.value = _backedIds.value + id
            // بيانات وصفية للكتب غير الموجودة في الفهرس (المدرَجة من ملفات أو المجلوبة من الموقع)
            userDb.extraBook(id)?.let { b ->
                val meta = JSONObject().put("book_name", b.name).put("author_name", b.authorName).put("author_id", b.authorIds.firstOrNull() ?: 0)
                    .put("category_name", b.categoryName).put("card", b.metaJson?.optString("card") ?: "").put("pages", b.file?.pages ?: 0)
                val jname = "$id.json"
                index().remove(jname)?.delete()
                d.createFile("application/json", jname)?.let { jf ->
                    context.contentResolver.openOutputStream(jf.uri, "w")?.use { it.write(meta.toString().toByteArray(Charsets.UTF_8)) }
                    synchronized(this@Backup) { cache?.put(jname, jf) }
                }
            }
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    /** استعادة كتاب من المجلد إلى مخزن التطبيق (دون شبكة). */
    suspend fun restoreBook(id: Int): Boolean = withContext(Dispatchers.IO) {
        if (!available()) return@withContext false
        val f = index()["$id.db.gz"] ?: index(refresh = true)["$id.db.gz"] ?: return@withContext false
        try {
            val tmp = File(library.tmpDir, "restore-$id.db")
            context.contentResolver.openInputStream(f.uri)?.use { ins ->
                GZIPInputStream(ins.buffered(1 shl 16)).use { gz -> tmp.outputStream().buffered(1 shl 16).use { gz.copyTo(it, 1 shl 16) } }
            } ?: return@withContext false
            // تحقق أنها قاعدة SQLite سليمة
            val header = ByteArray(16); tmp.inputStream().use { it.read(header) }
            if (!String(header, Charsets.US_ASCII).startsWith("SQLite format 3")) { tmp.delete(); return@withContext false }
            val dest = library.bookFile(id)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
            // بيانات الكتب غير الفهرسية
            if (library.book(id) == null) {
                val meta = index()["$id.json"]?.let { jf -> runCatching { context.contentResolver.openInputStream(jf.uri)?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) } }.getOrNull() }
                val m = HashMap<String, String>()
                if (meta != null) for (k in meta.keys()) m[k] = meta.optString(k)
                if (m["book_name"].isNullOrBlank()) runCatching { BookReader(id, dest).meta() }.getOrNull()?.let { m.putAll(it) }
                if (userDb.extraBook(id) == null) userDb.addExtraBook(id, m)
            }
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    /** نسخ كل الكتب المثبَّتة التي ليست في المجلد. يعيد (نُسخ، أخفق). */
    suspend fun backupAll(onProgress: suspend (Int, Int, String) -> Unit): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val have = listBackedUp(refresh = true).keys
        val todo = library.installedIds().filter { it !in have }.sorted()
        var ok = 0; var fail = 0
        todo.forEachIndexed { i, id ->
            currentCoroutineContext().ensureActive()
            val name = library.book(id)?.name ?: userDb.extraBook(id)?.name ?: "كتاب $id"
            onProgress(i + 1, todo.size, name)
            if (mirrorBook(id)) ok++ else fail++
        }
        ok to fail
    }

    /** استعادة كل كتب المجلد غير المثبَّتة. يعيد (استُعيد، أخفق). */
    suspend fun restoreAll(onProgress: suspend (Int, Int, String) -> Unit): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val installed = library.installedIds()
        val todo = listBackedUp(refresh = true).keys.filter { it !in installed }.sorted()
        var ok = 0; var fail = 0
        todo.forEachIndexed { i, id ->
            currentCoroutineContext().ensureActive()
            onProgress(i + 1, todo.size, library.book(id)?.name ?: "كتاب ${ArabicText.arabicDigits(id)}")
            if (restoreBook(id)) ok++ else fail++
        }
        if (ok > 0) library.bumpInstalled()
        ok to fail
    }
}
