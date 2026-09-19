import kotlinx.coroutines.runBlocking
import org.murabbie.shamela.data.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

fun main() = runBlocking {
    val out = java.io.PrintStream(java.io.FileOutputStream("/tmp/nastest.out"), true, "UTF-8")
    val murl = "https://files.murabbie.org/fsdownload/FnGtHqpG9/manifest.json"
    var ok = 0
    repeat(5) { runCatching { Manifest.parse(NasHttp.fetchText(murl)) }.onSuccess { if (it.packs.containsKey("books.pack")) ok++ }.onFailure { out.println("manifest err: $it") } }
    out.println("manifest via OkHttp: $ok/5")
    val m = Manifest.parse(NasHttp.fetchText(murl))
    val mf = m.master!!
    val gz = File("/tmp/nt_master.gz"); gz.delete()
    NasHttp.download(mf.url, gz, mf.size)
    out.println("master sha ok: " + (sha(gz.readBytes()) == mf.sha256))
    val db = File("/tmp/nt_master.db"); GZIPInputStream(gz.inputStream()).use { i -> db.outputStream().use { i.copyTo(it) } }
    val d = Db(db.path, true)
    val rows = d.query("select b.book_name, f.offset, f.length, f.size, f.sha256, f.pages from book b join book_file f on f.book_id=b.book_id order by random() limit 3") { r -> listOf(r.text(0), r.long(1), r.long(2), r.long(3), r.text(4), r.int(5)) }
    val purl = m.packs["books.pack"]!!.url
    var allok = true
    for (r in rows) {
        val f = File("/tmp/nt_book.gz"); f.delete()
        NasHttp.downloadRange(purl, f, r[1] as Long, r[2] as Long)
        val good = f.length() == (r[2] as Long) && sha(f.readBytes()) == (r[4] as String)
        val bdb = File("/tmp/nt_book.db"); GZIPInputStream(f.inputStream()).use { i -> bdb.outputStream().use { i.copyTo(it) } }
        val n = BookReader(0, bdb).pageCount()
        out.println("  ${(r[0] as String).take(28)}: dl=$good pages=${n == r[5]}")
        allok = allok && good && n == (r[5] as Int)
        BookStore.closeAll()
    }
    out.println(if (ok == 5 && allok) "OKHTTP END-TO-END: PASS" else "OKHTTP END-TO-END: FAIL")
}
