package org.murabbie.shamela.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.murabbie.shamela.data.ArabicText

/** ألوان العرض المستعملة في نص الصفحة */
data class PageColors(val heading: Color, val quote: Color, val ref: Color, val highlight: Color, val muted: Color)

/**
 * يحوّل نص صفحة الشاملة (أسطر مفصولة بـ \r مع وسوم span ذات data-type) إلى AnnotatedString للعرض.
 * الأنماط المعروفة: title (عناوين الفهرس)، وأنماط صفحات الموقع web-c2 (نصوص منقولة/أحاديث)،
 * web-c4 (مرجع آية)، web-c5 (تمييز)، web-special (رموز).
 */
object PageRenderer {

    private val tagRegex = Regex("<(/?)span([^>]*)>|<[^>]+>")
    private val typeRegex = Regex("data-type=\"([^\"]*)\"")
    private val rawiRegex = Regex("<a\\s+href=\"inr://man-(\\d+)\"[^>]*>", RegexOption.IGNORE_CASE)

    /** وسم التعليقات النصية للراوي (القيمة = معرِّف الراوي) */
    const val RAWI_TAG = "rawi"

    data class Rendered(val text: AnnotatedString, val plain: String)

    fun render(body: String, colors: PageColors, baseSize: TextUnit, showTashkeel: Boolean, highlightWords: List<String> = emptyList(), prefix: Boolean = false): Rendered {
        val src = body.replace("\r\n", "\n").replace('\r', '\n')
        val plainSb = StringBuilder(src.length)
        val styles = ArrayList<Triple<Int, Int, SpanStyle>>()
        val stack = ArrayList<Pair<String, Int>>() // (type, start)
        val rawiSpans = ArrayList<Triple<Int, Int, String>>()
        var rawiStart = -1; var rawiId = ""
        var i = 0
        for (m in tagRegex.findAll(src)) {
            appendText(plainSb, src.substring(i, m.range.first), showTashkeel)
            i = m.range.last + 1
            val rm = if (m.value.startsWith("<a", true)) rawiRegex.matchEntire(m.value) else null
            if (rm != null) { rawiStart = plainSb.length; rawiId = rm.groupValues[1]; continue }
            if (m.value.startsWith("</a", true)) { if (rawiStart >= 0 && plainSb.length > rawiStart) rawiSpans.add(Triple(rawiStart, plainSb.length, rawiId)); rawiStart = -1; continue }
            if (m.value.startsWith("<span")) {
                val type = typeRegex.find(m.groupValues[2])?.groupValues?.get(1) ?: ""
                stack.add(type to plainSb.length)
            } else if (m.value.startsWith("</span")) {
                if (stack.isNotEmpty()) {
                    val (type, start) = stack.removeAt(stack.size - 1)
                    val end = plainSb.length
                    if (end > start) styleFor(type, colors, baseSize)?.let { styles.add(Triple(start, end, it)) }
                }
            } else if (m.value.startsWith("<br", true) || m.value.startsWith("<p", true) || m.value.startsWith("</p", true) || m.value.startsWith("</tr", true)) {
                if (plainSb.isNotEmpty() && plainSb.last() != '\n') plainSb.append('\n')
            } else if (m.value.startsWith("</td", true) || m.value.startsWith("</th", true)) {
                plainSb.append("  |  ")
            }
        }
        appendText(plainSb, src.substring(i), showTashkeel)
        val plain = plainSb.toString().trim('\n')
        val ann = buildAnnotatedString {
            append(plain)
            for ((s, e, st) in styles) { val ee = minOf(e, plain.length); if (s < ee) addStyle(st, s, ee) }
            for ((s, e, id) in rawiSpans) { val ee = minOf(e, plain.length); if (s < ee) { addStyle(SpanStyle(color = colors.ref, textDecoration = TextDecoration.Underline), s, ee); addStringAnnotation(RAWI_TAG, id, s, ee) } }
            if (highlightWords.isNotEmpty()) for (r in ArabicText.findMatches(plain, highlightWords, prefix)) {
                if (r.last < plain.length) addStyle(SpanStyle(background = colors.highlight, fontWeight = FontWeight.Bold), r.first, r.last + 1)
            }
        }
        return Rendered(ann, plain)
    }

    private fun appendText(sb: StringBuilder, s: String, showTashkeel: Boolean) {
        val t = s.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        if (showTashkeel) sb.append(t) else sb.append(ArabicText.stripTashkeel(t))
    }

    private fun styleFor(type: String, c: PageColors, base: TextUnit): SpanStyle? = when (type) {
        "title" -> SpanStyle(color = c.heading, fontWeight = FontWeight.Bold, fontSize = (base.value * 1.12f).sp)
        "web-c2" -> SpanStyle(color = c.quote)
        "web-c4" -> SpanStyle(color = c.ref, fontSize = (base.value * 0.9f).sp)
        "web-c5" -> SpanStyle(fontWeight = FontWeight.Bold)
        "web-special" -> SpanStyle(color = c.quote)
        "quran", "aya" -> SpanStyle(color = c.ref, fontWeight = FontWeight.Medium)
        "hadith" -> SpanStyle(color = c.quote)
        "bold" -> SpanStyle(fontWeight = FontWeight.Bold)
        "underline" -> SpanStyle(textDecoration = TextDecoration.Underline)
        else -> null
    }

    /** نص الهامش */
    fun renderFoot(foot: String, colors: PageColors, showTashkeel: Boolean, highlightWords: List<String> = emptyList(), prefix: Boolean = false): AnnotatedString {
        val plain0 = ArabicText.stripTags(foot.replace("\r\n", "\n").replace('\r', '\n').replace(Regex("</t[dh]>", RegexOption.IGNORE_CASE), "  |  ").replace(Regex("</tr>", RegexOption.IGNORE_CASE), "\n"))
        val plain = if (showTashkeel) plain0 else ArabicText.stripTashkeel(plain0)
        return buildAnnotatedString {
            append(plain)
            if (highlightWords.isNotEmpty()) for (r in ArabicText.findMatches(plain, highlightWords, prefix)) {
                if (r.last < plain.length) addStyle(SpanStyle(background = colors.highlight, fontWeight = FontWeight.Bold), r.first, r.last + 1)
            }
        }
    }
}
