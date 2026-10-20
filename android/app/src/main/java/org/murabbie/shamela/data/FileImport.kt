package org.murabbie.shamela.data

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * إدراج كتاب جديد من ملف على الجهاز (Word .docx، PDF نصّي، نص .txt، EPUB) وبناء قاعدته بصيغة كتب الشاملة نفسها
 * (page/title/page_fts/title_fts/meta) ليُقرأ ويُبحث فيه ويُخرَج ويُشكَّل ويُقرأ صوتيًّا ككتب المكتبة.
 * الكتب المدرَجة تأخذ معرِّفات من 9000001 فصاعدًا حتى لا تتعارض مع معرِّفات الشاملة، وتُسجَّل في extra_book بقسم «كتب مدرَجة».
 */
object FileImport {

    const val FIRST_ID = 9_000_001
    const val CATEGORY = "كتب مدرَجة من ملفات"

    /** فقرة من الملف: نص، ومستوى عنوان (0 = نص عادي)، وهل تسبقها نهاية صفحة في الأصل */
    class Para(val text: String, val heading: Int = 0, val pageBreakBefore: Boolean = false)

    class Parsed(val paras: List<Para>, val title: String? = null, val author: String? = null, val hasPageBreaks: Boolean)

    enum class Kind { DOCX, PDF, TXT, EPUB }

    fun kindOf(name: String, mime: String?): Kind? {
        val n = name.lowercase()
        return when {
            n.endsWith(".docx") || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> Kind.DOCX
            n.endsWith(".pdf") || mime == "application/pdf" -> Kind.PDF
            n.endsWith(".epub") || mime == "application/epub+zip" -> Kind.EPUB
            n.endsWith(".txt") || mime?.startsWith("text/") == true -> Kind.TXT
            else -> null
        }
    }

    // ---------- Word ----------

    private fun parseDocx(input: InputStream): Parsed {
        var documentXml: ByteArray? = null; var stylesXml: ByteArray? = null; var coreXml: ByteArray? = null
        ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                when (e.name) { "word/document.xml" -> documentXml = z.readBytes(); "word/styles.xml" -> stylesXml = z.readBytes(); "docProps/core.xml" -> coreXml = z.readBytes() }
            }
        }
        val doc = documentXml ?: throw IllegalStateException("ملف Word غير صالح (لا يحوي document.xml)")
        // خريطة معرِّف النمط → مستوى العنوان
        val headingOf = HashMap<String, Int>()
        stylesXml?.let { sx ->
            val p = newParser(sx.inputStream()); var curId: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "style" -> curId = p.getAttributeValue(null, "styleId")
                    "name" -> { val n = p.getAttributeValue(null, "val")?.lowercase() ?: ""; val id = curId
                        if (id != null) { Regex("^heading (\\d)$").find(n)?.let { headingOf[id] = it.groupValues[1].toInt() }; if (n == "title") headingOf[id] = 1 } }
                }
            }
        }
        for (i in 1..9) { headingOf.putIfAbsent("Heading$i", i); headingOf.putIfAbsent("heading$i", i); headingOf.putIfAbsent("$i", i) }
        headingOf.putIfAbsent("Title", 1)
        val title = coreXml?.let { Regex("<dc:title>(.*?)</dc:title>").find(String(it, Charsets.UTF_8))?.groupValues?.get(1)?.let(::unescape)?.trim() }?.takeIf { it.isNotBlank() }
        val author = coreXml?.let { Regex("<dc:creator>(.*?)</dc:creator>").find(String(it, Charsets.UTF_8))?.groupValues?.get(1)?.let(::unescape)?.trim() }?.takeIf { it.isNotBlank() }
        val paras = ArrayList<Para>()
        val p = newParser(doc.inputStream())
        var sb = StringBuilder(); var heading = 0; var breakBefore = false; var pendingBreak = false; var inP = false; var anyBreak = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "p" -> { inP = true; sb = StringBuilder(); heading = 0; breakBefore = pendingBreak; pendingBreak = false }
                    "pStyle" -> heading = headingOf[p.getAttributeValue(null, "val") ?: ""] ?: 0
                    "t" -> if (inP) sb.append(p.nextText())
                    "tab" -> if (inP) sb.append(' ')
                    "br" -> { val t = p.getAttributeValue(null, "type"); if (t == "page") { anyBreak = true; if (sb.isBlank()) breakBefore = true else pendingBreak = true } else if (inP) sb.append('\n') }
                    "lastRenderedPageBreak" -> { anyBreak = true; if (sb.isBlank()) breakBefore = true else pendingBreak = true }
                }
                XmlPullParser.END_TAG -> if (p.name == "p" && inP) {
                    inP = false
                    val text = sb.toString().trim()
                    if (text.isNotEmpty()) paras.add(Para(text, heading, breakBefore)) else if (breakBefore) pendingBreak = true
                }
            }
        }
        return Parsed(paras, title, author, anyBreak)
    }

    private fun newParser(input: InputStream): XmlPullParser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser().apply { setInput(input, "UTF-8") }
    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")

    // ---------- PDF ----------

    private suspend fun parsePdf(context: Context, input: InputStream, onProgress: suspend (Int, Int, String) -> Unit): Parsed {
        PDFBoxResourceLoader.init(context.applicationContext)
        val paras = ArrayList<Para>()
        PDDocument.load(input).use { doc ->
            val n = doc.numberOfPages
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            var textChars = 0
            for (i in 1..n) {
                currentCoroutineContext().ensureActive()
                stripper.startPage = i; stripper.endPage = i
                val text = runCatching { stripper.getText(doc) }.getOrDefault("")
                var first = true
                for (line in text.split('\n')) { val t = line.trim(); if (t.isEmpty()) continue; textChars += t.length; paras.add(Para(t, 0, first)); first = false }
                if (first) paras.add(Para("", 0, true)) // صفحة بلا نص (مصوَّرة)
                if (i % 10 == 0) onProgress(i, n, "قراءة الصفحة ${ArabicText.arabicDigits(i)} من ${ArabicText.arabicDigits(n)}")
            }
            if (textChars < 200) throw IllegalStateException("هذا الملف PDF مصوَّر (بلا نص قابل للاستخراج)؛ يلزمه تعرّف ضوئي OCR قبل الإدراج")
            val title = doc.documentInformation?.title?.trim()?.takeIf { it.isNotBlank() }
            val author = doc.documentInformation?.author?.trim()?.takeIf { it.isNotBlank() }
            return Parsed(paras, title, author, true)
        }
    }

    // ---------- TXT / EPUB ----------

    private fun parseTxt(input: InputStream): Parsed {
        val text = input.bufferedReader(Charsets.UTF_8).readText()
        val paras = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map { Para(it) }
        return Parsed(paras, null, null, false)
    }

    private fun parseEpub(input: InputStream): Parsed {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { z -> while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) files[e.name] = z.readBytes() } }
        val container = files["META-INF/container.xml"]?.let { String(it, Charsets.UTF_8) } ?: ""
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: files.keys.firstOrNull { it.endsWith(".opf") } ?: throw IllegalStateException("ملف EPUB غير صالح")
        val opf = String(files[opfPath] ?: ByteArray(0), Charsets.UTF_8)
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val items = Regex("<item[^>]*id=\"([^\"]+)\"[^>]*href=\"([^\"]+)\"").findAll(opf).associate { it.groupValues[1] to it.groupValues[2] } +
            Regex("<item[^>]*href=\"([^\"]+)\"[^>]*id=\"([^\"]+)\"").findAll(opf).associate { it.groupValues[2] to it.groupValues[1] }
        val spine = Regex("<itemref[^>]*idref=\"([^\"]+)\"").findAll(opf).map { it.groupValues[1] }.toList()
        val title = Regex("<dc:title[^>]*>(.*?)</dc:title>").find(opf)?.groupValues?.get(1)?.let(::unescape)?.trim()
        val author = Regex("<dc:creator[^>]*>(.*?)</dc:creator>").find(opf)?.groupValues?.get(1)?.let(::unescape)?.trim()
        val paras = ArrayList<Para>()
        for (id in spine) {
            val href = items[id] ?: continue
            val html = files[base + href]?.let { String(it, Charsets.UTF_8) } ?: continue
            var first = true
            val body = html.substringAfter("<body", html).substringAfter(">")
            val blockRegex = Regex("<(h[1-6]|p|div|li|blockquote)[^>]*>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            var any = false
            for (m in blockRegex.findAll(body)) {
                val tag = m.groupValues[1].lowercase()
                val t = unescape(ArabicText.stripTags(m.groupValues[2].replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n"))).trim()
                if (t.isEmpty()) continue
                any = true
                paras.add(Para(t, if (tag.startsWith("h")) (tag[1] - '0').coerceIn(1, 3) else 0, first)); first = false
            }
            if (!any) { val t = unescape(ArabicText.stripTags(body)).trim(); if (t.isNotEmpty()) paras.add(Para(t, 0, true)) }
        }
        return Parsed(paras, title, author, true)
    }

    // ---------- البناء ----------

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * يدرج الملف كتابًا. يعيد معرِّف الكتاب الجديد.
     */
    suspend fun importBook(context: Context, library: Library, userDb: UserDb, uri: Uri, fileName: String, mime: String?, nameOverride: String?, authorOverride: String?, onProgress: suspend (Int, Int, String) -> Unit): Int = withContext(Dispatchers.IO) {
        val kind = kindOf(fileName, mime) ?: throw IllegalStateException("نوع الملف غير مدعوم (المدعوم: docx, pdf, txt, epub)")
        onProgress(0, 0, "قراءة الملف…")
        val parsed = context.contentResolver.openInputStream(uri)?.use { input ->
            when (kind) { Kind.DOCX -> parseDocx(input); Kind.PDF -> parsePdf(context, input, onProgress); Kind.TXT -> parseTxt(input); Kind.EPUB -> parseEpub(input) }
        } ?: throw IllegalStateException("تعذّر فتح الملف")
        if (parsed.paras.none { it.text.isNotBlank() }) throw IllegalStateException("لم يُعثر على نص في الملف")
        val name = nameOverride?.takeIf { it.isNotBlank() } ?: parsed.title ?: fileName.substringBeforeLast('.')
        val author = authorOverride?.takeIf { it.isNotBlank() } ?: parsed.author ?: ""
        val id = nextId(userDb)
        onProgress(0, 0, "بناء الكتاب…")
        // تقسيم إلى صفحات: بفواصل الصفحات الأصلية إن وُجدت، وإلا كل نحو ٢٢٠٠ حرف عند حدود الفقرات
        val pages = ArrayList<MutableList<Para>>()
        var cur = ArrayList<Para>(); var curLen = 0
        for (pa in parsed.paras) {
            val brk = if (parsed.hasPageBreaks) pa.pageBreakBefore else (curLen > 2200 || (pa.heading in 1..2 && curLen > 600))
            if (brk && cur.isNotEmpty()) { pages.add(cur); cur = ArrayList(); curLen = 0 }
            if (pa.text.isNotBlank()) { cur.add(pa); curLen += pa.text.length }
        }
        if (cur.isNotEmpty()) pages.add(cur)
        val dest = library.bookFile(id)
        val tmp = File(library.tmpDir, "import-$id.db").apply { delete() }
        val db = Db(tmp.path, readOnly = false)
        try {
            db.exec("PRAGMA journal_mode=OFF"); db.exec("PRAGMA synchronous=OFF")
            db.exec("CREATE TABLE page(id INTEGER PRIMARY KEY, part TEXT, page INTEGER, number INTEGER, body TEXT, foot TEXT, services TEXT)")
            db.exec("CREATE TABLE title(id INTEGER PRIMARY KEY, page INTEGER, parent INTEGER, text TEXT)")
            db.exec("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
            db.exec("CREATE VIRTUAL TABLE page_fts USING fts5(body, foot, content='', tokenize='unicode61 remove_diacritics 2')")
            db.exec("CREATE VIRTUAL TABLE title_fts USING fts5(text, content='', tokenize='unicode61 remove_diacritics 2')")
            var titleId = 0
            val parentAtLevel = IntArray(10)
            db.transaction { tx ->
                pages.forEachIndexed { i, ps ->
                    val pid = i + 1
                    val body = ps.joinToString("\r") { pa -> if (pa.heading > 0) "<span data-type=\"title\">${esc(pa.text)}</span>" else esc(pa.text) }
                    tx.exec("INSERT INTO page(id, part, page, number, body, foot, services) VALUES(?,?,?,?,?,NULL,NULL)", pid, null, pid, null, body)
                    tx.exec("INSERT INTO page_fts(rowid, body, foot) VALUES(?,?,?)", pid, ArabicText.normalize(ArabicText.stripTags(body)), "")
                    for (pa in ps) if (pa.heading > 0) {
                        titleId++
                        val lvl = pa.heading.coerceIn(1, 9)
                        val parent = if (lvl == 1) 0 else parentAtLevel[lvl - 1]
                        tx.exec("INSERT INTO title(id, page, parent, text) VALUES(?,?,?,?)", titleId, pid, parent, pa.text)
                        tx.exec("INSERT INTO title_fts(rowid, text) VALUES(?,?)", titleId, ArabicText.normalize(pa.text))
                        parentAtLevel[lvl] = titleId; for (k in lvl + 1..9) parentAtLevel[k] = titleId
                    }
                }
                if (titleId == 0) { tx.exec("INSERT INTO title(id, page, parent, text) VALUES(1,1,0,?)", name); tx.exec("INSERT INTO title_fts(rowid, text) VALUES(1,?)", ArabicText.normalize(name)) }
                tx.exec("CREATE INDEX title_page ON title(page)"); tx.exec("CREATE INDEX title_parent ON title(parent)"); tx.exec("CREATE INDEX page_partpage ON page(part, page)")
                val card = "الكتاب: $name" + (if (author.isNotBlank()) "\nالمؤلف: $author" else "") + "\nالمصدر: ملف ${kind.name.lowercase()} مدرَج من الجهاز ($fileName)\nعدد الصفحات: ${ArabicText.arabicDigits(pages.size)}"
                val kv = listOf("book_id" to "$id", "format" to "shamela-book", "format_version" to "1", "pages" to "${pages.size}", "titles" to "$titleId",
                    "source" to "file:$fileName", "normalizer" to "arabic-v1", "book_name" to name, "author_name" to author, "author_id" to "", "card" to card, "category_name" to CATEGORY)
                for ((k, v) in kv) tx.exec("INSERT OR REPLACE INTO meta(key, value) VALUES(?,?)", k, v)
            }
            db.exec("INSERT INTO page_fts(page_fts) VALUES('optimize')")
        } finally { db.close() }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
        userDb.addExtraBook(id, mapOf("book_name" to name, "author_name" to author, "category_name" to CATEGORY, "card" to "الكتاب: $name\nالمصدر: ملف مدرَج ($fileName)", "pages" to "${pages.size}"))
        library.bumpInstalled()
        onProgress(pages.size, pages.size, "اكتمل")
        id
    }

    private suspend fun nextId(userDb: UserDb): Int {
        val max = userDb.extraBooks().maxOfOrNull { it.id } ?: 0
        return maxOf(FIRST_ID, max + 1)
    }
}
