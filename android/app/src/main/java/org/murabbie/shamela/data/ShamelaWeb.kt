package org.murabbie.shamela.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.murabbie.shamela.BuildConfig
import java.io.File

/**
 * الاتصال بموقع shamela.ws: قائمة المستجدات (آخر الكتب المضافة)، وجلب كتاب كامل من الموقع
 * وبناء قاعدة SQLite له بالصيغة نفسها التي ينتجها المستخرج (page/title/FTS5/meta).
 */
object ShamelaWeb {
    private val site = BuildConfig.SHAMELA_SITE

    internal fun unescape(s: String): String = s
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }

    /** آخر الكتب المضافة من الصفحة الرئيسة */
    suspend fun recent(): List<RecentBook> = withContext(Dispatchers.IO) {
        val html = NasHttp.fetchText("$site/", browserUa = true)
        val start = html.indexOf("id=\"cat_books\"").takeIf { it >= 0 } ?: html.indexOf("id=\"recent\"")
        val section = if (start >= 0) html.substring(start) else html
        val item = Regex("<div class=\"book_item[^\"]*\">\\s*<a href=\"[^\"]*/book/(\\d+)\"[^>]*>(.*?)</a>\\s*\\[<a href=\"[^\"]*/author/(\\d+)\"[^>]*>(.*?)</a>]\\s*<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
        item.findAll(section).map { m ->
            RecentBook(
                id = m.groupValues[1].toInt(),
                name = unescape(ArabicText.stripTags(m.groupValues[2])).trim(),
                authorId = m.groupValues[3].toIntOrNull(),
                authorName = unescape(ArabicText.stripTags(m.groupValues[4])).trim(),
                card = unescape(m.groupValues[5].replace(Regex("<br\\s*/?>"), "\n").let { ArabicText.stripTags(it) }).trim(),
            )
        }.toList()
    }

    data class WebBookInfo(val id: Int, val name: String, val authorName: String, val authorId: Int?, val card: String, val categoryName: String, val pagesCount: Int, val firstPageId: Int, val lastPageId: Int)

    /** بطاقة الكتاب من صفحته */
    suspend fun bookInfo(id: Int): WebBookInfo = withContext(Dispatchers.IO) {
        val html = NasHttp.fetchText("$site/book/$id", browserUa = true)
        val name = Regex("<title>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { unescape(it) }?.removePrefix("كتاب ")?.substringBefore(" - المكتبة الشاملة")?.trim() ?: "كتاب $id"
        val author = Regex("href=\"[^\"]*/author/(\\d+)\"[^>]*>(.*?)</a>").find(html)
        val cardStart = html.indexOf("betaka-index").let { i -> if (i < 0) -1 else html.lastIndexOf("<div", i) }
        val card = Regex("الكتاب:(.*?)(</div>|<div class=\"betaka-index\")", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?.replace(Regex("<br\\s*/?>"), "\n")?.let { unescape(ArabicText.stripTags(it)) }?.trim()?.let { "الكتاب:$it" } ?: ""
        val cat = Regex("href=\"[^\"]*/category/\\d+\"[^>]*>(.*?)</a>").find(html)?.groupValues?.get(1)?.let { unescape(ArabicText.stripTags(it)).trim() } ?: ""
        val pages = Regex("عدد الصفحات:\\s*([٠-٩0-9]+)").find(html)?.groupValues?.get(1)?.let { ArabicText.normalize(it).toIntOrNull() } ?: 0
        // أول صفحة وآخرها من روابط التنقل في صفحة القراءة الأولى
        val first = Regex("href=\"[^\"]*/book/$id/(\\d+)\"").findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }.minOrNull() ?: 1
        WebBookInfo(id, name, author?.groupValues?.get(2)?.let { unescape(ArabicText.stripTags(it)).trim() } ?: "", author?.groupValues?.get(1)?.toIntOrNull(), card, cat, pages, first, -1)
    }

    private data class WebTitle(val id: Int, val page: Int, val parent: Int, val text: String)

    /** فهرس الموضوعات كاملًا (مع الفروع عبر ajax/titlechilds) */
    private suspend fun fetchToc(bookId: Int): List<WebTitle> {
        val html = NasHttp.fetchText("$site/book/$bookId", browserUa = true)
        val i = html.indexOf("class=\"betaka-index\"")
        val block = if (i < 0) "" else html.substring(i, html.indexOf("</div>", i).takeIf { it > 0 } ?: html.length)
        val out = ArrayList<WebTitle>()
        var nextSynthetic = 1_000_000
        suspend fun parseList(ul: String, parent: Int, depth: Int) {
            val li = Regex("<li>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
            for (m in li.findAll(ul)) {
                val s = m.groupValues[1]
                val dataId = Regex("data-id=\"(\\d+)\"").find(s)?.groupValues?.get(1)?.toIntOrNull()
                val link = Regex("href=\"[^\"]*/book/$bookId/(\\d+)\"[^>]*>(.*?)</a>").find(s) ?: continue
                val page = link.groupValues[1].toInt()
                val text = unescape(ArabicText.stripTags(link.groupValues[2])).trim()
                val id = dataId ?: (nextSynthetic++)
                out.add(WebTitle(id, page, parent, text))
                if (dataId != null && depth < 8) {
                    currentCoroutineContext().ensureActive()
                    val child = runCatching { NasHttp.fetchText("$site/ajax/titlechilds/$bookId/$dataId", attempts = 3, browserUa = true) }.getOrNull() ?: continue
                    parseList(child, id, depth + 1)
                }
            }
        }
        parseList(block, 0, 0)
        return out
    }

    /** تحويل HTML صفحة الموقع إلى صيغة نص الشاملة (أسطر بـ \r وعلامات span) */
    internal fun convertNass(nass: String): Pair<String, String?> {
        var h = nass.replace("\r", "").replace("\n", " ")
        // الهامش
        var foot: String? = null
        val hm = Regex("<hr\\s*/?>\\s*<p class=\"hamesh\">(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(h)
        if (hm != null) {
            foot = unescape(ArabicText.stripTags(hm.groupValues[1].replace(Regex("<br\\s*/?>"), "\r"))).trim()
            h = h.removeRange(hm.range)
        }
        h = h.replace(Regex("<a [^>]*class=\"btn_tag[^\"]*\"[^>]*>.*?</a>", RegexOption.DOT_MATCHES_ALL), "")
        h = h.replace(Regex("<span[^>]*class=\"anchor\"[^>]*></span>"), "")
        h = h.replace(Regex("<span (?:id=\"[^\"]*\" )?class=\"anchor\"[^>]*>"), "")
        h = h.replace(Regex("</?p[^>]*>")) { m -> if (m.value.startsWith("</")) "\r" else "" }
        h = h.replace(Regex("<br\\s*/?>"), "\r")
        h = h.replace(Regex("<span class=\"(c\\d|special)\">")) { m -> "<span data-type=\"web-${m.groupValues[1]}\">" }
        h = h.replace(Regex("<(?!/?span)[^>]+>"), "")
        val body = unescape(h).split('\r').joinToString("\r") { it.trim() }.trim('\r', ' ')
        return body to foot?.takeIf { it.isNotBlank() }
    }

    /**
     * جلب كتاب كاملًا من الموقع وبناء قاعدته في [dest].
     * onProgress(الصفحات المنجزة، الإجمالي التقديري، رسالة)
     */
    suspend fun importBook(bookId: Int, dest: File, onProgress: suspend (Int, Int, String) -> Unit) = withContext(Dispatchers.IO) {
        if (dest.exists()) dest.delete()
        val info = bookInfo(bookId)
        onProgress(0, info.pagesCount, "فهرس الموضوعات…")
        val toc = runCatching { fetchToc(bookId) }.getOrDefault(emptyList())
        val db = Db(dest.path, readOnly = false)
        try {
            db.exec("PRAGMA journal_mode=OFF"); db.exec("PRAGMA synchronous=OFF")
            db.exec("CREATE TABLE page(id INTEGER PRIMARY KEY, part TEXT, page INTEGER, number INTEGER, body TEXT, foot TEXT, services TEXT)")
            db.exec("CREATE TABLE title(id INTEGER PRIMARY KEY, page INTEGER, parent INTEGER, text TEXT)")
            db.exec("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
            db.exec("CREATE VIRTUAL TABLE page_fts USING fts5(body, foot, content='', tokenize='unicode61 remove_diacritics 2')")
            db.exec("CREATE VIRTUAL TABLE title_fts USING fts5(text, content='', tokenize='unicode61 remove_diacritics 2')")
            var pageId = info.firstPageId
            var lastNum = -1
            var part: String? = null
            var count = 0
            var guard = 0
            while (guard++ < 200000) {
                currentCoroutineContext().ensureActive()
                val js = runCatching { NasHttp.fetchText("$site/ajax/pageContent/$bookId/$pageId", attempts = 4, browserUa = true) }.getOrNull() ?: break
                val j = runCatching { JSONObject(js) }.getOrNull() ?: break
                val nass = j.optString("nass", "")
                val num = j.optInt("pageNum", 0)
                if (num in 1 until lastNum || part == null) {
                    // بداية جزء جديد (أو أول صفحة): نقرأ رقم الجزء من صفحة HTML
                    val html = runCatching { NasHttp.fetchText("$site/book/$bookId/$pageId", attempts = 3, browserUa = true) }.getOrNull() ?: ""
                    part = Regex("id=\"fld_part_top\" value=\"([^\"]*)\"").find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                    if (part == null && num in 1 until lastNum) part = null
                }
                lastNum = num
                val (body, foot) = convertNass(nass)
                db.transaction { tx ->
                    tx.exec("INSERT OR REPLACE INTO page(id, part, page, number, body, foot, services) VALUES(?,?,?,?,?,?,NULL)", pageId, part, if (num > 0) num else null, null, body, foot)
                    tx.exec("INSERT INTO page_fts(rowid, body, foot) VALUES(?,?,?)", pageId, ArabicText.normalize(body), ArabicText.normalize(foot))
                }
                count++
                if (count % 5 == 0) onProgress(count, maxOf(info.pagesCount, count), "الصفحة ${ArabicText.arabicDigits(count)}")
                val next = j.optString("nextId", "").toIntOrNull() ?: break
                if (next <= pageId) break
                pageId = next
            }
            if (count == 0) throw IllegalStateException("تعذر جلب صفحات الكتاب من الموقع")
            db.transaction { tx ->
                for (t in toc) {
                    tx.exec("INSERT OR REPLACE INTO title(id, page, parent, text) VALUES(?,?,?,?)", t.id, t.page, t.parent, t.text)
                    tx.exec("INSERT INTO title_fts(rowid, text) VALUES(?,?)", t.id, ArabicText.normalize(t.text))
                }
                tx.exec("CREATE INDEX title_page ON title(page)")
                tx.exec("CREATE INDEX title_parent ON title(parent)")
                tx.exec("CREATE INDEX page_partpage ON page(part, page)")
                val kv = listOf("book_id" to "$bookId", "format" to "shamela-book", "format_version" to "1", "pages" to "$count", "titles" to "${toc.size}",
                    "source" to "shamela.ws", "normalizer" to "arabic-v1", "book_name" to info.name, "author_name" to info.authorName,
                    "author_id" to (info.authorId?.toString() ?: ""), "card" to info.card, "category_name" to info.categoryName)
                for ((k, v) in kv) tx.exec("INSERT OR REPLACE INTO meta(key, value) VALUES(?,?)", k, v)
            }
            db.exec("INSERT INTO page_fts(page_fts) VALUES('optimize')")
            onProgress(count, count, "اكتمل")
        } finally {
            db.close()
        }
    }
}
