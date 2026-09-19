import kotlinx.coroutines.runBlocking
import org.murabbie.shamela.data.*
import java.io.File

val out = java.io.PrintStream(java.io.FileOutputStream("/tmp/jvmtest.out"), true, "UTF-8")
fun check(name: String, ok: Boolean, detail: String = "") { out.println((if (ok) "PASS " else "FAIL ") + name + (if (detail.isNotEmpty()) " — $detail" else "")); if (!ok) failures++ }
var failures = 0

fun main(args: Array<String>) = runBlocking {
    // التطبيع
    check("normalize", ArabicText.normalize("الأَحَادِيثُ <b>x</b> ﷺ ٣") == "الاحاديث  x  ﷺ 3", ArabicText.normalize("الأَحَادِيثُ <b>x</b> ﷺ ٣"))
    check("ftsQuery and", ArabicText.ftsQuery("صلاة الجماعة", 0, false) == "\"صلاه\" AND \"الجماعه\"", ArabicText.ftsQuery("صلاة الجماعة", 0, false) ?: "null")
    check("ftsQuery phrase prefix", ArabicText.ftsQuery("صلاة الجماعة", 2, true) == "\"صلاه الجماعه\"*", ArabicText.ftsQuery("صلاة الجماعة", 2, true) ?: "null")
    check("ftsQuery cols", ArabicText.ftsQuery("ذكر", 1, false, listOf("body")) == "{body} : (\"ذكر\")", ArabicText.ftsQuery("ذكر", 1, false, listOf("body")) ?: "")
    val text = "وقال الزَّرْكَشِيُّ: قد وهم من قال ذلك؛ الذِّكْرُ عظيم"
    val m = ArabicText.findMatches(text, listOf("الذكر", "الزركشي"), false)
    out.println(m.map { "${it.first}-${it.last}:" + text.substring(it) }); check("findMatches", m.size == 2 && text.substring(m[0]) == "الزَّرْكَشِيُّ" && text.substring(m[1]) == "الذِّكْرُ", m.map { text.substring(it) }.toString())
    // manifest
    val mf = Manifest.parse("""{"app":{"version_code":3,"version_name":"1.2","apk":"u"},"data":{"version":"1448-1","master":{"url":"m","size":10,"sha256":"x","raw_size":20},"packs":{"books-00.pack":{"url":"p0","size":5,"sha256":"h"}}}}""")
    check("manifest", mf.appVersionCode == 3 && mf.packs["books-00.pack"]?.url == "p0" && mf.master?.rawSize == 20L)
    // تحويل صفحة الموقع
    val nass = "<p><span id=\"p1\" class=\"anchor\"></span>نص <span class=\"c2\">«حديث»</span> <span class=\"c2\">(١)</span>.<a href=\"#p1\" class=\"btn_tag btn btn-sm\"><span class=\"text-gray fa fa-copy\"></span></a></p><p><span class=\"anchor\" id=\"p2\"></span>فقرة ثانية</p><hr><p class=\"hamesh\">(١) مرجع.<br />(٢) آخر.</p>"
    val (body, foot) = ShamelaWeb.convertNass(nass)
    check("convertNass body", body == "نص <span data-type=\"web-c2\">«حديث»</span> <span data-type=\"web-c2\">(١)</span>.\rفقرة ثانية", body)
    check("convertNass foot", foot == "(١) مرجع.\r(٢) آخر.", foot ?: "null")
    // قاعدة كتاب حقيقية + FTS5
    val dbPath = args.getOrNull(0) ?: "/tmp/webtest/30198.db"
    if (File(dbPath).exists()) {
        val r = BookReader(30198, File(dbPath))
        val n = r.pageCount(); check("pageCount", n > 0, "$n")
        val q = ArabicText.ftsQuery("ذكر الله", 0, false)!!
        val ids = r.searchPages(q); check("fts search", ids.isNotEmpty(), "${ids.size} pages")
        val p = r.page(ids.first())!!
        check("page label", p.label.isNotEmpty(), p.label)
        val t = r.titles(); check("titles tree", t.isNotEmpty() && t.any { it.level > 0 }, "${t.size} titles, levels=${t.map { it.level }.toSet()}")
        check("titleForPage", r.titleForPage(50) != null, r.titleForPage(50)?.text ?: "")
        val pf = r.pageIdFor(null, 55); check("pageIdFor", pf == 43, "$pf")
        val hits = r.searchTitles(ArabicText.ftsQuery("فضل", 1, true)!!); check("title fts", hits.isNotEmpty(), "${hits.size}")
    } else println("SKIP db tests ($dbPath missing)")
    out.println(if (failures == 0) "ALL PASSED" else "$failures FAILURES")
}
