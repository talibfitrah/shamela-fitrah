import kotlinx.coroutines.runBlocking
import org.murabbie.shamela.data.NasHttp
import org.murabbie.shamela.data.ShamelaWeb

fun main() = runBlocking {
    val home = NasHttp.fetchText("https://shamela.ws/", attempts = 3, browserUa = true)
    println("home len=${home.length} hasRecent=${home.contains("id=\"recent\"")}")
    val cat = NasHttp.fetchText("https://shamela.ws/category/40", attempts = 3, browserUa = true)
    println("cat40 books=${Regex("/book/(\\d+)\"").findAll(cat).count()}")
    val recent = ShamelaWeb.recent()
    println("recent=${recent.size} first=${recent.firstOrNull()?.name}")
    val m = org.murabbie.shamela.data.Manifest.parse(NasHttp.fetchText("https://files.murabbie.org/fsdownload/FnGtHqpG9/manifest.json"))
    println("manifest services=${m.services?.url} generated=${m.generated}")
    println(if (home.length > 1000 && recent.isNotEmpty() && m.services != null) "WEB TEST: PASS" else "WEB TEST: FAIL")
}
