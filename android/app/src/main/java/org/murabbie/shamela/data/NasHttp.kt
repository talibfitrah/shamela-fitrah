package org.murabbie.shamela.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * عميل HTTP لتنزيل ملفات NAS عبر روابط مشاركة DSM العامة — بلا أي حساب.
 *
 * علة الخادم: روابط /fsdownload تُرجِع من nginx خطأ 404 متقطعًا حين يذهب طلب تهيئة الجلسة
 * (/sharing/<id>) وطلب التنزيل (/fsdownload/<id>/<name>) إلى عاملَي DSM مختلفين، فلا يجد الثاني
 * جلسة المشاركة. الحلّ: تنفيذ الطلبين على **اتصال TCP واحد** (نفس العامل) عبر عميل مخصَّص لكل عملية
 * ببِركة اتصال خاصة به. ثبت هذا ٨/٨ للطلبات الكاملة وطلبات المدى.
 */
object NasHttp {

    const val UA = "Shamela-Android/1.0 (+https://murabbie.org)"

    /** المضيفون المحتملون للخادم بالترتيب؛ يُجرَّبون بالتناوب. الأول قد يُستبدل بعنوان يدخله المستخدم (شبكة داخلية). */
    @Volatile var bases: List<String> = listOf("https://files.murabbie.org")
    fun setCustomBase(base: String?) {
        val b = base?.trim()?.trimEnd('/')
        bases = if (b.isNullOrBlank()) listOf("https://files.murabbie.org")
        else listOf(if (b.startsWith("http")) b else "http://$b", "https://files.murabbie.org")
    }

    /** آخر حالة تشخيصية (تُعرض في شاشة الإعداد) */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    private val log = ArrayDeque<String>()
    fun note(s: String) { synchronized(log) { log.addLast(s); while (log.size > 8) log.removeFirst(); _status.value = log.joinToString("\n") } }
    fun clearLog() { synchronized(log) { log.clear(); _status.value = "" } }

    private val hostRegex = Regex("^https?://[^/]+")
    /** يعيد كتابة رابط المشاركة على مضيف آخر (الرمز يعمل على كل المضيفين). */
    private fun onBase(url: String, base: String): String = url.replace(hostRegex, base)
    private fun shortHost(url: String) = hostRegex.find(url)?.value?.removePrefix("https://")?.removePrefix("http://") ?: url

    private val fsdownloadRegex = Regex("^(https?://[^/]+)/fsdownload/([^/]+)/")

    private fun sharingPrefix(url: String): String? {
        val m = fsdownloadRegex.find(url) ?: return null
        return m.groupValues[1] + "/sharing/" + m.groupValues[2]
    }

    /** مخزن كعكات في الذاكرة لعملية واحدة — يحمل كعكة sharing_sid من التهيئة إلى التنزيل. */
    private class MemJar : CookieJar {
        private val store = HashMap<String, MutableList<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val l = store.getOrPut(url.host) { ArrayList() }
            for (c in cookies) { l.removeAll { it.name == c.name }; l.add(c) }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = (store[url.host] ?: emptyList()).filter { it.expiresAt > System.currentTimeMillis() }
    }

    /** عميل مخصَّص لعملية واحدة ببِركة اتصال وكعكات خاصة، ليضمن أن التهيئة والتنزيل على الاتصال نفسه وبالجلسة نفسها. */
    private fun opClient(): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(MemJar())
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    /** تهيئة جلسة المشاركة على العميل نفسه (يترك الاتصال في البِركة ليُعاد استعماله فورًا). */
    private fun prime(c: OkHttpClient, shareUrl: String) {
        val sharing = sharingPrefix(shareUrl) ?: return
        runCatching { c.newCall(Request.Builder().url(sharing).header("User-Agent", UA).header("Connection", "keep-alive").build()).execute().use { it.body?.bytes() } }
    }

    class HttpException(val code: Int, msg: String) : IOException(msg)

    private fun isErrorBody(ct: String, firstBytes: ByteArray): Boolean {
        if (ct.contains("text/html", true)) return true
        // DSM يعيد أحيانًا {"error":...} بنوع JSON عند فشل الجلسة
        if (ct.contains("application/json", true)) {
            val s = String(firstBytes, Charsets.UTF_8)
            if (s.contains("\"error\"") || s.contains("\"success\":false")) return true
        }
        return false
    }

    /** جلب نص ملف صغير (manifest.json) عبر رابط مشاركته — تهيئة+تنزيل على اتصال واحد مع إعادة المحاولة. */
    suspend fun fetchText(shareUrl0: String, attempts: Int = 12, browserUa: Boolean = false): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        val isShare = sharingPrefix(shareUrl0) != null
        for (i in 0 until attempts) {
            currentCoroutineContext().ensureActive()
            val shareUrl = if (isShare) onBase(shareUrl0, bases[i % bases.size]) else shareUrl0
            val c = opClient()
            try {
                note("محاولة ${i + 1}: ${shortHost(shareUrl)} — ${if (isShare) "تهيئة الجلسة…" else "جلب…"}")
                prime(c, shareUrl)
                val ua = if (browserUa) "Mozilla/5.0 (Linux; Android 13) Mobile" else UA
                c.newCall(Request.Builder().url(shareUrl).header("User-Agent", ua).header("Cache-Control", "no-cache").build()).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    val ct = resp.header("Content-Type") ?: ""
                    if (resp.isSuccessful && (!isShare || !isErrorBody(ct, bytes))) { note("نجح عبر ${shortHost(shareUrl)}"); return@withContext String(bytes, Charsets.UTF_8) }
                    note("رد ${resp.code} (${ct.substringBefore(';')}) من ${shortHost(shareUrl)}")
                    throw HttpException(resp.code, "HTTP ${resp.code}")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e !is HttpException) note("خطأ: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                last = e; delay(300L * (i + 1))
            } finally { runCatching { c.connectionPool.evictAll() } }
        }
        throw last ?: IOException("فشل الجلب")
    }

    /**
     * تنزيل مدى [start, start+length) من ملف عبر رابط مشاركته إلى [dest] مع الاستئناف.
     * كل محاولة على عميل/اتصال جديد (تهيئة ثم تنزيل) لتجنّب علة عمّال DSM.
     */
    suspend fun downloadRange(
        shareUrl0: String, dest: File, start: Long = 0, length: Long = -1, attempts: Int = 20,
        onProgress: suspend (Long, Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        var last: Exception? = null
        val isShare = sharingPrefix(shareUrl0) != null
        for (attempt in 0 until attempts) {
            currentCoroutineContext().ensureActive()
            val shareUrl = if (isShare) onBase(shareUrl0, bases[attempt % bases.size]) else shareUrl0
            var have = if (dest.exists()) dest.length() else 0L
            if (length > 0 && have >= length) { if (have > length) RandomAccessFile(dest, "rw").use { it.setLength(length) }; onProgress(length, length); return@withContext }
            val c = opClient()
            try {
                prime(c, shareUrl)
                val from = start + have
                val rangeHeader = if (length > 0) "bytes=$from-${start + length - 1}" else "bytes=$from-"
                c.newCall(Request.Builder().url(shareUrl).header("User-Agent", UA).header("Range", rangeHeader).header("Connection", "keep-alive").build()).execute().use { resp ->
                    val code = resp.code
                    val ct = resp.header("Content-Type") ?: ""
                    if (code == 416) { onProgress(have, have); return@withContext }
                    if (ct.contains("text/html", true)) throw HttpException(code, "جلسة المشاركة (أعيد المحاولة)")
                    if (!resp.isSuccessful) throw HttpException(code, "HTTP $code")
                    if (code != 206 && (start > 0 || have > 0)) throw IOException("لا دعم للمدى")
                    val body = resp.body ?: throw IOException("لا محتوى")
                    val total = if (length > 0) length else {
                        val cr = resp.header("Content-Range") ?: ""
                        (cr.substringAfter('/', "").toLongOrNull()?.minus(start)) ?: (have + body.contentLength())
                    }
                    RandomAccessFile(dest, "rw").use { raf ->
                        raf.setLength(have); raf.seek(have)
                        val buf = ByteArray(256 * 1024)
                        body.byteStream().use { input ->
                            var lastReport = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf); if (n < 0) break
                                raf.write(buf, 0, n); have += n
                                if (have - lastReport > 256 * 1024) { lastReport = have; onProgress(have, total) }
                                if (length > 0 && have >= length) break
                            }
                        }
                    }
                    val finalSize = dest.length()
                    if (length > 0 && finalSize < length) throw IOException("انقطع التنزيل عند $finalSize من $length")
                    onProgress(finalSize, if (total > 0) total else finalSize)
                    return@withContext
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                note("تنزيل: ${shortHost(shareUrl)} — ${e.javaClass.simpleName}: ${e.message?.take(60)}")
                last = e; delay(minOf(8000L, 300L * (attempt + 1)))
            } finally { runCatching { c.connectionPool.evictAll() } }
        }
        throw last ?: IOException("فشل التنزيل")
    }

    suspend fun download(shareUrl: String, dest: File, expectedSize: Long = -1, onProgress: suspend (Long, Long) -> Unit = { _, _ -> }) =
        downloadRange(shareUrl, dest, 0, expectedSize, onProgress = onProgress)

    fun sha256(file: File, onProgress: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            var done = 0L
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); done += n; onProgress(done) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
