package org.murabbie.shamela.data

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** ما يُصدَّر */
data class ExportRequest(
    val book: Book,
    val pageIds: List<Int>,          // معرِّفات الصفحات بالترتيب
    val title: String,               // عنوان الملف/المقطع (اسم الكتاب أو الفصل)
    val format: Format,
    val includeFoot: Boolean = true,
    val showTashkeel: Boolean = true,
    val pageBreaks: Boolean = false,  // فاصل صفحة عند كل صفحة من الكتاب (وورد)
) { enum class Format { DOCX, PDF } }

/**
 * إخراج الكتب أو مقاطع منها (صفحات/فصول) إلى Word (.docx) أو PDF.
 * Word: ملف OOXML مكتوب مباشرة (بلا مكتبات): اتجاه RTL، خط Traditional Arabic، متن ١٦ وعناوين ٢٤/١٩، محاذاة يمين، تباعد ٢٤٠،
 * عناوين الفصول بأنماط Heading ليظهر فهرس Word، ورقم كل صفحة من المطبوع في سطر صغير.
 * PDF: تصفيف نصي بخط النظام العربي على قياس A4 مع ترقيم وترويسة باسم الكتاب.
 */
object Exporter {

    fun exportDir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply { mkdirs() }

    private fun safeName(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), " ").trim().take(80).ifBlank { "shamela" }

    /** ينفّذ التصدير ويعيد الملف الناتج */
    suspend fun export(context: Context, reader: BookReader, req: ExportRequest, onProgress: (Int, Int) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        val ext = if (req.format == ExportRequest.Format.DOCX) "docx" else "pdf"
        val out = File(exportDir(context), safeName(req.title) + "." + ext)
        val titles = reader.titles()
        val titleByPage = HashMap<Int, MutableList<Title>>()
        for (t in titles) titleByPage.getOrPut(t.page) { ArrayList() }.add(t)
        when (req.format) {
            ExportRequest.Format.DOCX -> writeDocx(out, reader, req, titleByPage, onProgress)
            ExportRequest.Format.PDF -> writePdf(out, reader, req, titleByPage, onProgress)
        }
        out
    }

    // ---------- تحضير النص ----------

    private fun plainBody(p: Page, tashkeel: Boolean): String {
        val t = ArabicText.stripTags(p.body.replace("\r\n", "\n").replace('\r', '\n').replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n"))
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        return if (tashkeel) t else ArabicText.stripTashkeel(t)
    }
    private fun plainFoot(p: Page, tashkeel: Boolean): String? {
        val f = p.foot?.takeIf { it.isNotBlank() } ?: return null
        val t = ArabicText.stripTags(f.replace("\r\n", "\n").replace('\r', '\n')).trim()
        return if (tashkeel) t else ArabicText.stripTashkeel(t)
    }

    // ---------- Word ----------

    private fun xml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .replace(Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]"), "")

    private fun para(text: String, style: String? = null, size: Int = 16, bold: Boolean = false, color: String? = null): String {
        val pPr = StringBuilder("<w:pPr>")
        if (style != null) pPr.append("<w:pStyle w:val=\"$style\"/>")
        pPr.append("<w:bidi/><w:spacing w:line=\"240\" w:lineRule=\"auto\" w:after=\"0\"/><w:jc w:val=\"right\"/></w:pPr>")
        val rPr = StringBuilder("<w:rPr><w:rFonts w:ascii=\"Traditional Arabic\" w:hAnsi=\"Traditional Arabic\" w:cs=\"Traditional Arabic\"/>")
        if (bold) rPr.append("<w:b/><w:bCs/>")
        if (color != null) rPr.append("<w:color w:val=\"$color\"/>")
        if (size > 0) rPr.append("<w:sz w:val=\"${size * 2}\"/><w:szCs w:val=\"${size * 2}\"/>")
        rPr.append("<w:rtl/></w:rPr>")
        return "<w:p>$pPr<w:r>$rPr<w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r></w:p>"
    }

    private suspend fun writeDocx(out: File, reader: BookReader, req: ExportRequest, titleByPage: Map<Int, List<Title>>, onProgress: (Int, Int) -> Unit) {
        ZipOutputStream(FileOutputStream(out).buffered(1 shl 16)).use { zip ->
            fun entry(name: String, content: String) { zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>""")
            entry("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/></Relationships>""")
            entry("docProps/core.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>${xml(req.title)}</dc:title><dc:creator>${xml(req.book.authorName)}</dc:creator><dc:description>${xml("المكتبة الشاملة — " + req.book.name)}</dc:description></cp:coreProperties>""")
            entry("word/settings.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:defaultTabStop w:val="720"/><w:themeFontLang w:val="en-US" w:bidi="ar-SA"/></w:settings>""")
            val font = "<w:rFonts w:ascii=\"Traditional Arabic\" w:hAnsi=\"Traditional Arabic\" w:cs=\"Traditional Arabic\"/>"
            entry("word/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr>$font<w:sz w:val="32"/><w:szCs w:val="32"/><w:lang w:val="en-US" w:bidi="ar-SA"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:bidi/><w:jc w:val="right"/><w:spacing w:line="240" w:lineRule="auto" w:after="0"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:qFormat/><w:rPr>$font<w:b/><w:bCs/><w:sz w:val="48"/><w:szCs w:val="48"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:outlineLvl w:val="0"/><w:spacing w:before="240" w:after="0" w:line="240" w:lineRule="auto"/><w:bidi/><w:jc w:val="right"/></w:pPr><w:rPr>$font<w:b/><w:bCs/><w:sz w:val="42"/><w:szCs w:val="42"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:outlineLvl w:val="1"/><w:spacing w:before="160" w:after="0" w:line="240" w:lineRule="auto"/><w:bidi/><w:jc w:val="right"/></w:pPr><w:rPr>$font<w:b/><w:bCs/><w:sz w:val="38"/><w:szCs w:val="38"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:outlineLvl w:val="2"/><w:spacing w:before="120" w:after="0" w:line="240" w:lineRule="auto"/><w:bidi/><w:jc w:val="right"/></w:pPr><w:rPr>$font<w:b/><w:bCs/><w:sz w:val="36"/><w:szCs w:val="36"/></w:rPr></w:style></w:styles>""")

            zip.putNextEntry(ZipEntry("word/document.xml"))
            val w = zip.bufferedWriter(Charsets.UTF_8)
            w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
            w.write(para(req.book.name, "Title", 24, true))
            if (req.book.authorName.isNotBlank()) w.write(para(req.book.authorName, null, 18, true))
            if (req.title != req.book.name) w.write(para(req.title, null, 17, true))
            w.write(para("", null, 16))
            var n = 0
            for (chunk in req.pageIds.chunked(50)) {
                currentCoroutineContext().ensureActive()
                for (p in reader.pages(chunk)) {
                    if (req.pageBreaks && n > 0) w.write("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                    for (t in titleByPage[p.id].orEmpty()) w.write(para(t.text, "Heading" + (t.level + 1).coerceIn(1, 3), 0, true))
                    for (line in plainBody(p, req.showTashkeel).split('\n')) { if (line.isBlank()) continue; w.write(para(line, null, 16)) }
                    if (req.includeFoot) plainFoot(p, req.showTashkeel)?.let { f ->
                        w.write(para("__________", null, 8, false, "808080"))
                        for (line in f.split('\n')) if (line.isNotBlank()) w.write(para(line, null, 8))
                    }
                    w.write(para("[" + req.book.shortName + "، " + p.label + "]", null, 8, false, "808080"))
                    n++
                    onProgress(n, req.pageIds.size)
                }
                w.flush()
            }
            w.write("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1418" w:right="1418" w:bottom="1418" w:left="1418" w:header="709" w:footer="709" w:gutter="0"/><w:bidi/></w:sectPr></w:body></w:document>""")
            w.flush()
            zip.closeEntry()
        }
    }

    // ---------- PDF ----------

    private suspend fun writePdf(out: File, reader: BookReader, req: ExportRequest, titleByPage: Map<Int, List<Title>>, onProgress: (Int, Int) -> Unit) {
        val pageW = 595; val pageH = 842; val margin = 48
        val width = pageW - margin * 2
        val doc = PdfDocument()
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; color = Color.BLACK; typeface = Typeface.SERIF }
        val small = TextPaint(body).apply { textSize = 9.5f; color = Color.DKGRAY }
        val head = TextPaint(body).apply { textSize = 17f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        val title = TextPaint(body).apply { textSize = 22f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        val header = TextPaint(body).apply { textSize = 8.5f; color = Color.GRAY }

        var pdfPage: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f
        var pageNo = 0
        fun newPage() {
            pdfPage?.let { doc.finishPage(it) }
            pageNo++
            val pg = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            pdfPage = pg; canvas = pg.canvas; y = margin.toFloat()
            // ترويسة
            val hl = StaticLayout.Builder.obtain(req.book.shortName, 0, req.book.shortName.length, header, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(android.text.TextDirectionHeuristics.RTL).build()
            canvas!!.save(); canvas!!.translate(margin.toFloat(), (margin - 28).toFloat()); hl.draw(canvas!!); canvas!!.restore()
            val num = ArabicText.arabicDigits(pageNo)
            canvas!!.drawText(num, (pageW / 2).toFloat(), (pageH - 22).toFloat(), header.apply { textAlign = Paint.Align.CENTER })
            header.textAlign = Paint.Align.LEFT
        }
        fun draw(text: String, paint: TextPaint, spacingAfter: Float = 4f, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) {
            if (text.isBlank()) return
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(align).setTextDirection(android.text.TextDirectionHeuristics.RTL).setLineSpacing(0f, 1.25f).build()
            // رسم سطرًا سطرًا مع كسر الصفحات
            var line = 0
            while (line < layout.lineCount) {
                if (canvas == null) newPage()
                val top = layout.getLineTop(line); val bottom = layout.getLineBottom(line)
                val h = (bottom - top).toFloat()
                if (y + h > pageH - margin) { newPage(); continue }
                canvas!!.save()
                canvas!!.translate(margin.toFloat(), y - top)
                canvas!!.clipRect(0, top, width, bottom)
                layout.draw(canvas!!)
                canvas!!.restore()
                y += h
                line++
            }
            y += spacingAfter
        }
        newPage()
        draw(req.book.name, title, 6f, Layout.Alignment.ALIGN_CENTER)
        if (req.book.authorName.isNotBlank()) draw(req.book.authorName, head, 4f, Layout.Alignment.ALIGN_CENTER)
        if (req.title != req.book.name) draw(req.title, head, 10f, Layout.Alignment.ALIGN_CENTER)
        y += 12f
        var n = 0
        for (chunk in req.pageIds.chunked(50)) {
            currentCoroutineContext().ensureActive()
            for (p in reader.pages(chunk)) {
                for (t in titleByPage[p.id].orEmpty()) draw(t.text, head, 6f)
                for (line in plainBody(p, req.showTashkeel).split('\n')) draw(line, body, 3f)
                if (req.includeFoot) plainFoot(p, req.showTashkeel)?.let { f -> draw("__________", small, 2f); for (l in f.split('\n')) draw(l, small, 2f) }
                draw("[" + req.book.shortName + "، " + p.label + "]", small, 10f)
                n++
                onProgress(n, req.pageIds.size)
            }
        }
        pdfPage?.let { doc.finishPage(it) }
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }
}
