package org.murabbie.shamela.data

/**
 * تطبيع النص العربي — يطابق Norm.java في المستخرج حرفًا بحرف، لأن فهارس FTS5 في قواعد الكتب
 * بُنيت على النص المطبَّع (بلا تشكيل، توحيد الهمزات والألف المقصورة والتاء المربوطة، أرقام لاتينية).
 */
object ArabicText {

    fun isRemovable(c: Char): Boolean {
        val code = c.code
        return (code in 0x0610..0x061A) || (code in 0x064B..0x065F) || code == 0x0670 ||
            (code in 0x06D6..0x06ED) || code == 0x0640 || (code in 0x200B..0x200F) ||
            (code in 0x202A..0x202E) || code == 0xFEFF
    }

    fun mapChar(c: Char): Char = when (c) {
        'أ', 'إ', 'آ', 'ٱ' -> 'ا'
        'ى', 'ی', 'ئ' -> 'ي'
        'ة' -> 'ه'
        'ؤ' -> 'و'
        'ک', 'گ' -> 'ك'
        in '٠'..'٩' -> ('0' + (c - '٠'))
        in '۰'..'۹' -> ('0' + (c - '۰'))
        else -> c.lowercaseChar()
    }

    /** إزالة الوسوم ثم التطبيع (كما في المستخرج). */
    fun normalize(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val sb = StringBuilder(s.length)
        var inTag = false
        for (c in s) {
            if (inTag) { if (c == '>') { inTag = false; sb.append(' ') }; continue }
            if (c == '<') { inTag = true; continue }
            if (isRemovable(c)) continue
            sb.append(mapChar(c))
        }
        return sb.toString()
    }

    /** تطبيع نص خالٍ من الوسوم مع خريطة من موضع الحرف المطبَّع إلى موضعه في الأصل (للتظليل). */
    fun normalizeWithMap(s: String): Pair<String, IntArray> {
        val sb = StringBuilder(s.length)
        val map = IntArray(s.length)
        var n = 0
        for (i in s.indices) {
            val c = s[i]
            if (isRemovable(c)) continue
            sb.append(mapChar(c))
            map[n++] = i
        }
        return sb.toString() to map.copyOf(n)
    }

    /** إزالة وسوم HTML مع الإبقاء على النص. */
    fun stripTags(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val sb = StringBuilder(s.length)
        var inTag = false
        for (c in s) {
            if (inTag) { if (c == '>') inTag = false; continue }
            if (c == '<') { inTag = true; continue }
            sb.append(c)
        }
        return sb.toString()
    }

    /** إزالة التشكيل فقط (للعرض بلا تشكيل). */
    fun stripTashkeel(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val code = c.code
            if ((code in 0x064B..0x065F) || code == 0x0670 || (code in 0x06D6..0x06ED) || (code in 0x0610..0x061A)) continue
            sb.append(c)
        }
        return sb.toString()
    }

    private val wordSplit = Regex("[\\s\\p{Punct}،؛؟«»\"'“”‘’()\\[\\]{}<>:.,!?…ـ/\\\\|~*+=_-]+")

    /** كلمات الاستعلام بعد التطبيع (مع الإبقاء على * في آخر الكلمة للبادئات). */
    fun queryWords(query: String): List<String> {
        val q = normalize(query.replace('*', ''))
        return q.split(wordSplit).map { it.trim() }.filter { it.isNotEmpty() }.map { it.replace('', '*') }
    }

    /**
     * بناء استعلام FTS5 من نص المستخدم.
     * mode: 0 كل الكلمات (AND)، 1 أي كلمة (OR)، 2 عبارة كاملة (مرتبة ومتجاورة).
     * prefix: مطابقة البادئات (كلمة*).
     */
    fun ftsQuery(query: String, mode: Int, prefix: Boolean, columns: List<String>? = null): String? {
        val words = queryWords(query).map { it.trimEnd('*') }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        fun term(w: String): String = "\"" + w.replace("\"", "\"\"") + "\"" + (if (prefix) "*" else "")
        val body = when (mode) {
            2 -> "\"" + words.joinToString(" ") { it.replace("\"", "\"\"") } + "\"" + (if (prefix) "*" else "")
            1 -> words.joinToString(" OR ") { term(it) }
            else -> words.joinToString(" AND ") { term(it) }
        }
        if (columns.isNullOrEmpty()) return body
        return "{" + columns.joinToString(" ") + "} : (" + body + ")"
    }

    /** مواضع تطابق كلمات الاستعلام في نص أصلي (بعد التطبيع مع الخريطة) — قائمة (بداية، نهاية) في الأصل. */
    fun findMatches(original: String, words: List<String>, prefix: Boolean): List<IntRange> {
        if (original.isEmpty() || words.isEmpty()) return emptyList()
        val (norm, map) = normalizeWithMap(original)
        val out = ArrayList<IntRange>()
        for (w0 in words) {
            val w = w0.trimEnd('*')
            if (w.isEmpty()) continue
            var from = 0
            while (true) {
                val i = norm.indexOf(w, from)
                if (i < 0) break
                // مطابقة على حدود الكلمات
                val startOk = i == 0 || !isWordChar(norm[i - 1])
                var end = i + w.length
                if (prefix || w0.endsWith("*")) {
                    while (end < norm.length && isWordChar(norm[end])) end++
                }
                val endOk = end >= norm.length || !isWordChar(norm[end])
                if (startOk && endOk && end - 1 < map.size) {
                    var last = map[end - 1]
                    while (last + 1 < original.length && isRemovable(original[last + 1])) last++ // ضم التشكيل اللاحق
                    out.add(map[i]..last)
                }
                from = i + 1
            }
        }
        return out.sortedBy { it.first }
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** الأرقام الهندية للعرض. */
    fun arabicDigits(n: Long): String = arabicDigits(n.toString())
    fun arabicDigits(n: Int): String = arabicDigits(n.toLong())
    fun arabicDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '0'..'9') ('٠' + (c - '0')) else c)
        return sb.toString()
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "؟"
        val kb = 1024.0; val mb = kb * 1024; val gb = mb * 1024
        val (v, unit) = when {
            bytes >= gb -> String.format(java.util.Locale.US, "%.2f", bytes / gb) to "غ.ب"
            bytes >= mb -> String.format(java.util.Locale.US, "%.1f", bytes / mb) to "م.ب"
            bytes >= kb -> String.format(java.util.Locale.US, "%.0f", bytes / kb) to "ك.ب"
            else -> bytes.toString() to "بايت"
        }
        return arabicDigits(v.replace('.', '٫')) + " " + unit
    }

    /** تاريخ بصيغة الشاملة ddmmyyyy (هجري) إلى نص. */
    fun formatShamelaDate(d: String?): String {
        if (d == null || d.length != 8) return ""
        val dd = d.substring(0, 2).toIntOrNull() ?: return ""
        val mm = d.substring(2, 4).toIntOrNull() ?: return ""
        val yy = d.substring(4).toIntOrNull() ?: return ""
        return "${arabicDigits(dd)}/${arabicDigits(mm)}/${arabicDigits(yy)} هـ"
    }

    /** سنة الوفاة من رقمها (99999 = مجهول/معاصر). */
    fun deathText(death: Int, text: String?): String {
        if (!text.isNullOrBlank()) return "ت " + arabicDigits(text) + " هـ"
        if (death <= 0 || death >= 99999) return ""
        return "ت " + arabicDigits(death) + " هـ"
    }
}
