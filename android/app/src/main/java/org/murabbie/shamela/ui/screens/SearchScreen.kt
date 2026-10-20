package org.murabbie.shamela.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.murabbie.shamela.App
import org.murabbie.shamela.data.ArabicText
import org.murabbie.shamela.data.Book
import org.murabbie.shamela.data.Category
import org.murabbie.shamela.data.Search
import org.murabbie.shamela.data.SearchEvent
import org.murabbie.shamela.data.SearchHit
import org.murabbie.shamela.data.SearchOptions
import org.murabbie.shamela.ui.Chip
import org.murabbie.shamela.ui.EmptyState
import org.murabbie.shamela.ui.SearchField
import org.murabbie.shamela.ui.go
import org.murabbie.shamela.ui.highlighted

/** نطاق البحث */
private sealed class Scope {
    object AllInstalled : Scope()
    data class OneBook(val book: Book) : Scope()
    data class InCategory(val category: Category) : Scope()
    data class Selected(val ids: Set<Int>) : Scope()
}

/**
 * ذاكرة نتائج البحث بين التنقلات (على مستوى التطبيق): عند فتح نتيجة ثم العودة تُستعاد القائمة نفسها
 * بنتائجها وموضع التمرير وخيارات العرض، دون إعادة البحث. ذاكرة مستقلة للبحث العام ولكل بحث داخل كتاب.
 */
private object SearchMemory {
    class Saved(
        var query: String = "",
        var scope: Scope = Scope.AllInstalled,
        var hits: List<SearchHit> = emptyList(),
        var done: SearchEvent.Done? = null,
        var groupByBook: Boolean = true,
        var expandedBooks: Set<Int> = emptySet(),
        var scrollIndex: Int = 0,
        var scrollOffset: Int = 0,
    )
    private val map = HashMap<Int, Saved>()
    fun of(bookId: Int?): Saved = synchronized(map) { map.getOrPut(bookId ?: -1) { Saved() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(app: App, nav: NavHostController, bookId: Int?, initialQuery: String?) {
    val settings by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val mem = remember { SearchMemory.of(bookId) }
    var query by remember { mutableStateOf(if (!initialQuery.isNullOrBlank()) initialQuery else mem.query) }
    var searchScope by remember { mutableStateOf(mem.scope) }
    var hits by remember { mutableStateOf(mem.hits) }
    var progress by remember { mutableStateOf<SearchEvent.Progress?>(null) }
    var done by remember { mutableStateOf(mem.done) }
    var running by remember { mutableStateOf<Job?>(null) }
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    var showScope by remember { mutableStateOf(false) }
    var groupByBook by remember { mutableStateOf(mem.groupByBook) }
    var expandedBooks by remember { mutableStateOf(mem.expandedBooks) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(mem.scrollIndex, mem.scrollOffset)
    val installedVer by app.library.installedVersion.collectAsState()

    // حفظ الحالة عند مغادرة الشاشة (فتح نتيجة) لتُستعاد عند العودة
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            mem.query = query; mem.scope = searchScope; mem.hits = hits; mem.done = done
            mem.groupByBook = groupByBook; mem.expandedBooks = expandedBooks
            mem.scrollIndex = listState.firstVisibleItemIndex; mem.scrollOffset = listState.firstVisibleItemScrollOffset
        }
    }

    LaunchedEffect(bookId) { if (bookId != null && (searchScope as? Scope.OneBook)?.book?.id != bookId) app.book(bookId)?.let { searchScope = Scope.OneBook(it) } }
    LaunchedEffect(Unit) { history = app.userDb.searches().map { it.query } }

    suspend fun booksForScope(): List<Book> {
        val installed = app.library.installedIds()
        return when (val s = searchScope) {
            is Scope.OneBook -> listOf(s.book)
            is Scope.InCategory -> app.library.booksInCategory(s.category.id).filter { it.id in installed }
            is Scope.Selected -> app.library.booksByIds(s.ids.filter { it in installed })
            Scope.AllInstalled -> app.library.booksByIds(installed) + app.userDb.extraBooks().filter { it.id in installed }
        }
    }

    fun run() {
        val q = query.trim()
        if (q.isBlank()) return
        running?.cancel()
        hits = emptyList(); done = null; progress = null; expandedBooks = emptySet()
        mem.scrollIndex = 0; mem.scrollOffset = 0
        running = scope.launch {
            runCatching { listState.scrollToItem(0) }
            app.userDb.addSearch(q); history = app.userDb.searches().map { it.query }
            val books = booksForScope()
            if (books.isEmpty()) { done = SearchEvent.Done(0, 0); return@launch }
            val opt = SearchOptions(q, settings.searchMode, settings.searchPrefix, inBody = true, inFoot = settings.searchInFoot, titlesOnly = settings.searchInTitles, byRoot = settings.searchByRoot)
            val acc = ArrayList<SearchHit>()
            Search.run(app.library, books, opt, app.services).collect { ev ->
                when (ev) {
                    is SearchEvent.Hits -> { acc.addAll(ev.hits); hits = ArrayList(acc) }
                    is SearchEvent.Progress -> progress = ev
                    is SearchEvent.Done -> done = ev
                    is SearchEvent.Error -> done = SearchEvent.Done(acc.size, 0)
                }
            }
        }
    }

    LaunchedEffect(Unit) { if (!initialQuery.isNullOrBlank() && hits.isEmpty() && done == null) run() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (bookId != null) "بحث في الكتاب" else "البحث في المكتبة") },
            navigationIcon = { if (bookId != null) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } },
            actions = { IconButton(onClick = { showScope = true }) { Icon(Icons.Default.FilterList, contentDescription = "النطاق") } },
        )
        SearchField(query, { query = it }, "اكتب كلمة أو أكثر…", onSearch = { run() })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("كل الكلمات", settings.searchMode == 0) { app.settings.update { it.copy(searchMode = 0) } }
            Chip("أي كلمة", settings.searchMode == 1) { app.settings.update { it.copy(searchMode = 1) } }
            Chip("عبارة", settings.searchMode == 2) { app.settings.update { it.copy(searchMode = 2) } }
            Chip("بادئات", settings.searchPrefix) { app.settings.update { it.copy(searchPrefix = !it.searchPrefix) } }
            Chip("الهوامش", settings.searchInFoot) { app.settings.update { it.copy(searchInFoot = !it.searchInFoot) } }
            Chip("العناوين فقط", settings.searchInTitles) { app.settings.update { it.copy(searchInTitles = !it.searchInTitles) } }
            Chip("بالجذر", settings.searchByRoot) { if (app.services.installed) app.settings.update { it.copy(searchByRoot = !it.searchByRoot) } else nav.go("services") }
        }
        val scopeLabel = when (val s = searchScope) { Scope.AllInstalled -> "كل الكتب المثبَّتة"; is Scope.OneBook -> s.book.name; is Scope.InCategory -> "قسم: " + s.category.name; is Scope.Selected -> "${ArabicText.arabicDigits(s.ids.size)} كتب مختارة" }
        Text("النطاق: $scopeLabel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable { showScope = true })
        val p = progress
        if (running?.isActive == true && p != null) {
            LinearProgressIndicator(progress = { if (p.booksTotal > 0) p.booksDone.toFloat() / p.booksTotal else 0f }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Text("فُحص ${ArabicText.arabicDigits(p.booksDone)} من ${ArabicText.arabicDigits(p.booksTotal)} كتاب — ${ArabicText.arabicDigits(p.hits)} نتيجة", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        val d = done
        if (d != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ArabicText.arabicDigits(d.hits)} نتيجة في ${ArabicText.arabicDigits(d.booksWithHits)} كتاب", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (hits.isNotEmpty()) TextButton(onClick = { groupByBook = !groupByBook }) { Text(if (groupByBook) "عرض متسلسل" else "تجميع بالكتاب") }
        }
        if (hits.isEmpty() && d == null && running?.isActive != true) {
            if (history.isNotEmpty()) {
                Text("عمليات بحث سابقة", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp))
                LazyColumn { items(history) { h -> Text(h, Modifier.fillMaxWidth().clickable { query = h; run() }.padding(horizontal = 16.dp, vertical = 10.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant) } }
            } else EmptyState("اكتب كلمات البحث ثم اضغط بحث.\nيُبحث في الكتب المثبَّتة على الجهاز (نزّل الكتب أولًا من المكتبة).")
        } else if (hits.isEmpty() && d != null) {
            EmptyState(if (installedVer >= 0 && app.library.installedIds().isEmpty()) "لا كتب مثبَّتة على الجهاز بعد" else "لا نتائج")
        } else {
            val hl = MaterialTheme.colorScheme.secondaryContainer
            if (groupByBook) {
                val groups = remember(hits) { hits.groupBy { it.bookId } }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    for ((bid, list) in groups) {
                        val first = list.first()
                        val expanded = bid in expandedBooks || groups.size == 1
                        item(key = "b$bid") {
                            Row(Modifier.fillMaxWidth().clickable { expandedBooks = if (expanded) expandedBooks - bid else expandedBooks + bid }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(first.bookName, style = MaterialTheme.typography.titleSmall)
                                    if (first.authorName.isNotBlank()) Text(first.authorName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(ArabicText.arabicDigits(list.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(if (expanded) "▾" else "▸", color = MaterialTheme.colorScheme.primary)
                            }
                            HorizontalDivider()
                        }
                        if (expanded) items(list) { h -> HitRow(h, hl, showBook = false) { nav.go("reader/${h.bookId}/${h.pageId}?q=${android.net.Uri.encode(query)}") } }
                    }
                }
            } else LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(hits) { h -> HitRow(h, hl, showBook = true) { nav.go("reader/${h.bookId}/${h.pageId}?q=${android.net.Uri.encode(query)}") } }
            }
        }
    }

    if (showScope) ScopeSheet(app, bookId, onDismiss = { showScope = false }) { s -> searchScope = s; showScope = false }
}

@Composable
private fun HitRow(h: SearchHit, hl: androidx.compose.ui.graphics.Color, showBook: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (showBook) Text(h.bookName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(highlighted(h.snippet, h.ranges, hl), style = MaterialTheme.typography.bodyMedium)
        Text((if (h.inFoot) "في الهامش — " else "") + h.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeSheet(app: App, bookId: Int?, onDismiss: () -> Unit, onPick: (Scope) -> Unit) {
    var cats by remember { mutableStateOf<List<Category>>(emptyList()) }
    var installed by remember { mutableStateOf<List<Book>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var filter by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        cats = app.library.categories()
        val ids = app.library.installedIds()
        installed = (app.library.booksByIds(ids) + app.userDb.extraBooks().filter { it.id in ids }).sortedBy { it.name }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().height(600.dp)) {
            Text("نطاق البحث", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            Row(Modifier.padding(horizontal = 16.dp)) {
                Chip("الكل", tab == 0) { tab = 0 }; Chip("قسم", tab == 1) { tab = 1 }; Chip("كتب مختارة", tab == 2) { tab = 2 }
            }
            when (tab) {
                0 -> Text("كل الكتب المثبَّتة (${ArabicText.arabicDigits(installed.size)})", Modifier.fillMaxWidth().clickable { onPick(Scope.AllInstalled) }.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                1 -> LazyColumn { items(cats, key = { it.id }) { c -> Text(c.name, Modifier.fillMaxWidth().clickable { onPick(Scope.InCategory(c)) }.padding(horizontal = 16.dp, vertical = 10.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant) } }
                2 -> Column {
                    SearchField(filter, { filter = it }, "تصفية")
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { if (selected.isNotEmpty()) onPick(Scope.Selected(selected)) }, enabled = selected.isNotEmpty()) { Text("بحث في ${ArabicText.arabicDigits(selected.size)} كتب") }
                        TextButton(onClick = { selected = emptySet() }) { Text("مسح") }
                    }
                    val words = ArabicText.queryWords(filter)
                    val list = if (words.isEmpty()) installed else installed.filter { b -> val n = ArabicText.normalize(b.name + " " + b.authorName); words.all { n.contains(it.trimEnd('*')) } }
                    LazyColumn(Modifier.weight(1f)) {
                        items(list, key = { it.id }) { b ->
                            Row(Modifier.fillMaxWidth().clickable { selected = if (b.id in selected) selected - b.id else selected + b.id }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = b.id in selected, onCheckedChange = { selected = if (it) selected + b.id else selected - b.id })
                                Column { Text(b.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(b.authorName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }
}
