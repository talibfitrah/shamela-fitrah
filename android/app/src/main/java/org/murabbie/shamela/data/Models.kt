package org.murabbie.shamela.data

import org.json.JSONObject

data class Category(val id: Int, val name: String, val order: Int, val booksCount: Int = 0)

data class Author(val id: Int, val name: String, val death: Int, val deathText: String?, val booksCount: Int = 0) {
    val deathLabel: String get() = ArabicText.deathText(death, deathText)
}

/** موضع ملف الكتاب داخل حزم NAS. */
data class BookFile(val pack: String, val offset: Long, val length: Long, val size: Long, val sha256: String, val pages: Int, val titles: Int)

data class Book(
    val id: Int,
    val name: String,
    val category: Int,
    val type: Int,
    val date: Int,
    val authorIds: List<Int>,
    val mainAuthor: Int,
    val authorName: String,
    val printed: Int,
    val groupId: Int?,
    val pdfLinks: String?,
    val meta: String?,
    val file: BookFile?,
    val categoryName: String = "",
) {
    val metaJson: JSONObject? by lazy { runCatching { if (meta.isNullOrBlank()) null else JSONObject(meta) }.getOrNull() }
    /** عنوان قصير (بلا لاحقة الطبعة) إن وُجد في meta */
    val shortName: String get() = metaJson?.optString("prefix").takeUnless { it.isNullOrBlank() } ?: name
    val edition: String get() = metaJson?.optString("suffix") ?: ""
    val addedDate: String get() = ArabicText.formatShamelaDate(metaJson?.optString("date"))
    val pagesCount: Int get() = file?.pages ?: 0
    val hasFile: Boolean get() = file != null
    /** روابط PDF (إن وُجدت) */
    val pdfUrls: List<String> get() {
        val j = runCatching { if (pdfLinks.isNullOrBlank()) null else JSONObject(pdfLinks) }.getOrNull() ?: return emptyList()
        val root = j.optString("root", "")
        val arr = j.optJSONArray("files") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val f = arr.optString(i).substringBefore('|')
            if (f.isBlank()) continue
            out.add(if (f.startsWith("http")) f else if (root.isNotBlank()) root + f else "https://shamela.ws/files$f")
        }
        return out
    }
    /** الاختصارات المعرَّفة في meta (مثل {"0": "[الشرح]"}) */
    val shorts: Map<String, String> get() {
        val s = metaJson?.optJSONObject("shorts") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (k in s.keys()) out[k] = s.optString(k)
        return out
    }
}

/** صفحة من كتاب. */
data class Page(val id: Int, val part: String?, val page: Int?, val number: Int?, val body: String, val foot: String?, val services: String? = null) {
    val label: String get() {
        val p = page?.let { ArabicText.arabicDigits(it) } ?: ""
        val j = part?.takeIf { it.isNotBlank() }?.let { ArabicText.arabicDigits(it) }
        return if (j != null && p.isNotEmpty()) "ج$j ص$p" else if (p.isNotEmpty()) "ص$p" else "صفحة ${ArabicText.arabicDigits(id)}"
    }
}

/** عنوان في فهرس الكتاب. */
data class Title(val id: Int, val page: Int, val parent: Int, val text: String, val level: Int = 0, val hasChildren: Boolean = false)

/** نتيجة بحث. */
data class SearchHit(val bookId: Int, val bookName: String, val authorName: String, val pageId: Int, val label: String, val snippet: String, val ranges: List<IntRange>, val inFoot: Boolean)

/** كتاب من قائمة المستجدات في shamela.ws */
data class RecentBook(val id: Int, val name: String, val authorId: Int?, val authorName: String, val card: String)

/** حالة ملف كتاب على الجهاز */
enum class BookState { NOT_DOWNLOADED, QUEUED, DOWNLOADING, INSTALLED, ON_PHONE, UNAVAILABLE }

/** ملف manifest.json على NAS. path = المسار على NAS (للواجهة الموثَّقة)، url = رابط المشاركة (احتياطي). */
data class ManifestPack(val file: String, val url: String, val path: String, val size: Long, val sha256: String)
data class ManifestFile(val url: String, val path: String, val size: Long, val sha256: String, val rawSize: Long)
data class Manifest(
    val appVersionCode: Int,
    val appVersionName: String,
    val apkUrl: String?,
    val apkPage: String?,
    val notes: String?,
    val dataVersion: String,
    val master: ManifestFile?,
    val services: ManifestFile?,
    val tashkeel: ManifestFile?,
    val packs: Map<String, ManifestPack>,
    val nasDir: String,
    val siteRecent: String,
    val generated: String,
    val raw: String,
) {
    companion object {
        fun parse(text: String): Manifest {
            val j = JSONObject(text)
            val app = j.optJSONObject("app") ?: JSONObject()
            val data = j.optJSONObject("data") ?: JSONObject()
            val nas = j.optJSONObject("nas")
            val dir = nas?.optString("dir")?.takeIf { it.isNotBlank() } ?: "/downloads/shamela"
            val m = data.optJSONObject("master")
            val packs = LinkedHashMap<String, ManifestPack>()
            val pj = data.optJSONObject("packs")
            if (pj != null) for (k in pj.keys()) {
                val o = pj.getJSONObject(k)
                packs[k] = ManifestPack(k, o.optString("url"), o.optString("path").ifBlank { "$dir/$k" }, o.optLong("size", -1), o.optString("sha256"))
            }
            val site = j.optJSONObject("site")
            return Manifest(
                appVersionCode = app.optInt("version_code", 0),
                appVersionName = app.optString("version_name", ""),
                apkUrl = app.optString("apk").takeIf { it.isNotBlank() },
                apkPage = app.optString("apk_page").takeIf { it.isNotBlank() },
                notes = app.optString("notes").takeIf { it.isNotBlank() },
                dataVersion = data.optString("version", ""),
                master = m?.let { ManifestFile(it.optString("url"), it.optString("path").ifBlank { "$dir/master.db.gz" }, it.optLong("size", -1), it.optString("sha256"), it.optLong("raw_size", -1)) },
                tashkeel = data.optJSONObject("tashkeel")?.let { ManifestFile(it.optString("url"), it.optString("path").ifBlank { "$dir/tashkeel.db.gz" }, it.optLong("size", -1), it.optString("sha256"), it.optLong("raw_size", -1)) },
                services = data.optJSONObject("services")?.let { ManifestFile(it.optString("url"), it.optString("path").ifBlank { "$dir/services.db.gz" }, it.optLong("size", -1), it.optString("sha256"), it.optLong("raw_size", -1)) },
                packs = packs,
                nasDir = dir,
                siteRecent = site?.optString("recent")?.takeIf { it.isNotBlank() } ?: "https://shamela.ws",
                generated = j.optString("generated", ""),
                raw = text,
            )
        }
    }
}
