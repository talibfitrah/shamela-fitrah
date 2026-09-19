package org.murabbie.shamela.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

data class SearchOptions(
    val query: String,
    val mode: Int = 0,            // 0 كل الكلمات، 1 أي كلمة، 2 عبارة
    val prefix: Boolean = false,
    val inBody: Boolean = true,
    val inFoot: Boolean = true,
    val titlesOnly: Boolean = false,
    val byRoot: Boolean = false,      // البحث بالجذر: كل مشتقات الكلمة (يحتاج قاعدة الخدمات)
    val perBookLimit: Int = 300,
    val totalLimit: Int = 3000,
)

sealed class SearchEvent {
    data class Progress(val booksDone: Int, val booksTotal: Int, val hits: Int) : SearchEvent()
    data class Hits(val hits: List<SearchHit>) : SearchEvent()
    data class Done(val hits: Int, val booksWithHits: Int) : SearchEvent()
    data class Error(val message: String) : SearchEvent()
}

/** محرك البحث عبر الكتب المثبَّتة: يفتح قاعدة كل كتاب ويستعلم فهرس FTS5 فيه ويبني المقتطفات. */
object Search {

    /** توسيع كلمات الاستعلام إلى مشتقات جذورها (البحث بالاشتقاق كما في نسخة ويندوز) */
    suspend fun expandByRoot(services: Services, query: String, perWord: Int = 300): List<List<String>> {
        val words = ArabicText.queryWords(query).map { it.trimEnd('*') }.filter { it.isNotEmpty() }
        return words.map { w ->
            val roots = runCatching { services.rootsOf(w) }.getOrDefault(emptyList())
            val toks = LinkedHashSet<String>(); toks.add(w)
            for (r in roots) toks.addAll(runCatching { services.tokensOfRoot(r, perWord) }.getOrDefault(emptyList()))
            toks.toList().take(perWord)
        }
    }

    fun run(library: Library, books: List<Book>, opt: SearchOptions, services: Services? = null): Flow<SearchEvent> = flow {
        val cols = ArrayList<String>()
        if (!opt.titlesOnly) { if (opt.inBody) cols.add("body"); if (opt.inFoot) cols.add("foot") }
        var words = ArabicText.queryWords(opt.query)
        var fts = ArabicText.ftsQuery(opt.query, opt.mode, opt.prefix, if (opt.titlesOnly || cols.size == 2) null else cols)
        if (opt.byRoot && services != null && services.installed && opt.mode != 2) {
            val groups = expandByRoot(services, opt.query)
            if (groups.isNotEmpty()) {
                val body = groups.joinToString(if (opt.mode == 1) " OR " else " AND ") { g -> "(" + g.joinToString(" OR ") { "\"" + it.replace("\"", "\"\"") + "\"" } + ")" }
                fts = if (opt.titlesOnly || cols.size == 2) body else "{" + cols.joinToString(" ") + "} : (" + body + ")"
                words = groups.flatten().distinct().take(400)
            }
        }
        if (fts == null) { emit(SearchEvent.Done(0, 0)); return@flow }
        val channel = Channel<Any>(Channel.UNLIMITED)
        var total = 0; var booksWithHits = 0; var done = 0
        coroutineScope {
            val producer = launch(Dispatchers.IO) {
                val queue = Channel<Book>(Channel.UNLIMITED)
                for (b in books) queue.send(b)
                queue.close()
                val workers = List(3) {
                    launch {
                        for (b in queue) {
                            currentCoroutineContext().ensureActive()
                            val hits = runCatching { searchBook(library, b, fts, words, opt) }.getOrElse { emptyList() }
                            channel.send(b to hits)
                        }
                    }
                }
                workers.forEach { it.join() }
                channel.close()
            }
            for (item in channel) {
                @Suppress("UNCHECKED_CAST")
                val pair = item as Pair<Book, List<SearchHit>>
                val hits = pair.second
                done++
                if (hits.isNotEmpty()) { booksWithHits++; total += hits.size; emit(SearchEvent.Hits(hits)) }
                emit(SearchEvent.Progress(done, books.size, total))
                if (total >= opt.totalLimit) { producer.cancel(); break }
            }
        }
        emit(SearchEvent.Done(total, booksWithHits))
    }.flowOn(Dispatchers.Default)

    private suspend fun searchBook(library: Library, book: Book, fts: String, words: List<String>, opt: SearchOptions): List<SearchHit> {
        val reader = BookReader(book.id, library.bookFile(book.id))
        if (opt.titlesOnly) {
            return reader.searchTitles(fts, opt.perBookLimit).map { t ->
                SearchHit(book.id, book.name, book.authorName, t.page, "", t.text, ArabicText.findMatches(t.text, words, opt.prefix), false)
            }
        }
        val ids = reader.searchPages(fts, opt.perBookLimit)
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<SearchHit>(ids.size)
        for (chunk in ids.chunked(100)) {
            for (p in reader.pages(chunk)) {
                val snippetBody = if (opt.inBody) snippet(p.body, words, opt.prefix) else null
                val snippetFoot = if (opt.inFoot && snippetBody == null) snippet(p.foot ?: "", words, opt.prefix) else null
                val (text, ranges, inFoot) = when {
                    snippetBody != null -> Triple(snippetBody.first, snippetBody.second, false)
                    snippetFoot != null -> Triple(snippetFoot.first, snippetFoot.second, true)
                    else -> Triple(plain(p.body).take(160), emptyList(), false)
                }
                out.add(SearchHit(book.id, book.name, book.authorName, p.id, p.label, text, ranges, inFoot))
            }
        }
        return out
    }

    fun plain(html: String): String = ArabicText.stripTags(html).replace('\r', ' ').replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    /** مقتطف حول أول تطابق مع مواضع التظليل داخله. */
    fun snippet(html: String, words: List<String>, prefix: Boolean, width: Int = 90): Pair<String, List<IntRange>>? {
        if (html.isEmpty()) return null
        val text = plain(html)
        val matches = ArabicText.findMatches(text, words, prefix)
        if (matches.isEmpty()) return null
        val first = matches.first()
        var start = (first.first - width).coerceAtLeast(0)
        var end = (first.last + width).coerceAtMost(text.length - 1)
        // على حدود الكلمات
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        while (end < text.length - 1 && !text[end + 1].isWhitespace()) end++
        val sub = (if (start > 0) "… " else "") + text.substring(start, end + 1) + (if (end < text.length - 1) " …" else "")
        val shift = (if (start > 0) 2 else 0) - start
        val ranges = matches.filter { it.first >= start && it.last <= end }.map { (it.first + shift)..(it.last + shift) }
        return sub to ranges
    }
}
