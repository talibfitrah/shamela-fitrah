package org.murabbie.shamela.data

/**
 * أقسام خاصة بأعلام مختارين: كتب العَلَم نفسه (من الفهرس ومن مستجدات الموقع)،
 * وما أُلِّف حوله من شروح وتحقيقات ودراسات (بالبحث في أسماء الكتب).
 */
data class Scholar(
    val key: String,
    val name: String,
    val title: String,
    /** معرِّف المؤلف في فهرس الشاملة */
    val authorId: Int,
    /** أنماط تُبحث في أسماء الكتب لكتب الآخرين حوله */
    val patterns: List<String>,
    /** أنماط تُستبعد (تشابه أسماء) */
    val exclude: List<String> = emptyList(),
)

object Scholars {
    val all = listOf(
        Scholar("ibn-taymiyyah", "ابن تيمية", "شيخ الإسلام أحمد بن عبد الحليم الحراني (ت ٧٢٨ هـ)", 54, listOf("ابن تيمية", "بن تيمية")),
        Scholar("ibn-alqayyim", "ابن القيم", "الإمام محمد بن أبي بكر ابن قيم الجوزية (ت ٧٥١ هـ)", 14, listOf("ابن القيم", "لابن القيم", "ابن قيم")),
        Scholar("albani", "الألباني", "الإمام محمد ناصر الدين الألباني (ت ١٤٢٠ هـ)", 414, listOf("الألباني")),
        Scholar("ibn-baz", "ابن باز", "الشيخ عبد العزيز بن عبد الله بن باز (ت ١٤٢٠ هـ)", 67, listOf("ابن باز", "بن باز")),
        Scholar("ibn-uthaymeen", "ابن عثيمين", "الشيخ محمد بن صالح العثيمين (ت ١٤٢١ هـ)", 57, listOf("ابن عثيمين", "بن عثيمين", "العثيمينية", "محمد بن صالح العثيمين")),
    )
    /** تُعرض هذه الأقسام في قائمة الأقسام بعد القسم ذي هذا المعرِّف («مسائل فقهية») وقبل «السياسة الشرعية والقضاء» */
    const val AFTER_CATEGORY = 19
    fun byKey(key: String): Scholar? = all.firstOrNull { it.key == key }
}
