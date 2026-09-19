package org.murabbie.shamela.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.launch
import org.murabbie.shamela.App
import org.murabbie.shamela.data.ArabicText
import org.murabbie.shamela.data.Book
import org.murabbie.shamela.data.Scholar
import org.murabbie.shamela.data.Scholars
import org.murabbie.shamela.ui.BookRow
import org.murabbie.shamela.ui.EmptyState
import org.murabbie.shamela.ui.Loading
import org.murabbie.shamela.ui.SearchField
import org.murabbie.shamela.ui.SectionHeader
import org.murabbie.shamela.ui.bookState
import org.murabbie.shamela.ui.go

/** صفوف الأقسام الخاصة بالأعلام — تُدرج في قائمة الأقسام بين «مسائل فقهية» و«السياسة الشرعية والقضاء». */
@Composable
fun ScholarRows(app: App, nav: NavHostController) {
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val installedVer by app.library.installedVersion.collectAsState()
    val scan by app.newBooks.scan.collectAsState()
    LaunchedEffect(installedVer, scan) {
        counts = Scholars.all.associate { s -> s.key to (app.library.booksByAuthor(s.authorId).size + app.userDb.extraBooksByAuthor(s.authorId).size) }
    }
    Scholars.all.forEach { s ->
        Row(Modifier.fillMaxWidth().clickable { nav.go("scholar/${s.key}") }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("كتب ${s.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            counts[s.key]?.let { Text(ArabicText.arabicDigits(it) + " كتاب", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

/**
 * قسم خاص بعَلَم: مؤلفاته (من الفهرس ومن مستجدات الموقع) مجمَّعة بأقسام المكتبة، وما أُلِّف حوله من شروح وتحقيقات ودراسات.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScholarScreen(app: App, nav: NavHostController, key: String) {
    val scholar = Scholars.byKey(key) ?: run { EmptyState("القسم غير موجود"); return }
    val scope = rememberCoroutineScope()
    var own by remember { mutableStateOf<List<Book>?>(null) }
    var fresh by remember { mutableStateOf<List<Book>>(emptyList()) }
    var about by remember { mutableStateOf<List<Book>>(emptyList()) }
    var q by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }   // 0 مؤلفاته، 1 حوله
    var confirmAll by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Book?>(null) }
    val jobs by app.downloads.jobs.collectAsState()
    val queued by app.downloads.queued.collectAsState()
    val installedVer by app.library.installedVersion.collectAsState()
    val installed = remember(installedVer) { app.library.installedIds() }
    val scan by app.newBooks.scan.collectAsState()

    LaunchedEffect(key, scan) {
        val o = app.library.booksByAuthor(scholar.authorId)
        own = o
        val ids = o.map { it.id }.toSet()
        fresh = app.userDb.extraBooksByAuthor(scholar.authorId).filter { it.id !in ids }
        about = app.library.booksNamedLike(scholar.patterns, scholar.authorId)
    }

    fun filter(list: List<Book>): List<Book> {
        val words = ArabicText.queryWords(q)
        return if (words.isEmpty()) list else list.filter { b -> val n = ArabicText.normalize(b.name + " " + b.authorName + " " + b.categoryName); words.all { n.contains(it.trimEnd('*')) } }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Column { Text("كتب ${scholar.name}", maxLines = 1, overflow = TextOverflow.Ellipsis); Text(scholar.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } },
            actions = { IconButton(onClick = { confirmAll = true }) { Icon(Icons.Default.CloudDownload, contentDescription = "تنزيل كتبه كلها") } },
        )
        val ownList = own ?: return Loading()
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("مؤلفاته (${ArabicText.arabicDigits(ownList.size + fresh.size)})") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("شروح ودراسات حوله (${ArabicText.arabicDigits(about.size)})") })
        }
        SearchField(q, { q = it }, "بحث في كتب ${scholar.name}")
        LazyColumn(Modifier.fillMaxSize()) {
            if (tab == 0) {
                val f = filter(fresh)
                if (f.isNotEmpty()) {
                    item { SectionHeader("مستجدات الموقع (لم تصل إلى الحزمة بعد)") }
                    items(f, key = { "w${it.id}" }) { b -> Entry(app, nav, b, b.id in installed, b.id in queued, jobs[b.id], true) { confirm = b } }
                }
                val grouped = filter(ownList).groupBy { it.categoryName.ifBlank { "غير مصنَّف" } }
                if (grouped.isEmpty() && f.isEmpty()) item { EmptyState("لا نتائج", Modifier.height(160.dp)) }
                grouped.forEach { (cat, books) ->
                    item(key = "h$cat") { SectionHeader("$cat (${ArabicText.arabicDigits(books.size)})") }
                    items(books, key = { it.id }) { b -> Entry(app, nav, b, b.id in installed, b.id in queued, jobs[b.id], false) { confirm = b } }
                }
            } else {
                val a = filter(about)
                item {
                    Card(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("كتب لمؤلفين آخرين تدور حول ${scholar.name}: شروح كتبه ومختصراتها وتحقيقاته ودراسات عنه — استُخرجت من أسماء الكتب.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                    }
                }
                if (a.isEmpty()) item { EmptyState("لا نتائج", Modifier.height(160.dp)) }
                items(a, key = { it.id }) { b -> Entry(app, nav, b, b.id in installed, b.id in queued, jobs[b.id], true) { confirm = b } }
            }
        }
    }

    if (confirmAll) {
        val list = (own ?: emptyList()) + fresh
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("تنزيل كل كتب ${scholar.name}") },
            text = { Text("تنزيل ${ArabicText.arabicDigits(list.size)} كتابًا (${ArabicText.formatSize(list.sumOf { it.file?.length ?: 0L })} من الخادم" + (if (fresh.isNotEmpty()) "، و${ArabicText.arabicDigits(fresh.size)} من الموقع" else "") + ") للاستخدام دون اتصال؟") },
            confirmButton = { TextButton(onClick = { scope.launch { app.downloads.enqueueAll(list) }; confirmAll = false }) { Text("تنزيل") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("إلغاء") } },
        )
    }
    confirm?.let { b ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(b.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            text = { Text(if (b.file != null && b.file.pack.isNotBlank()) "تنزيل الكتاب من الخادم (${ArabicText.formatSize(b.file.length)})؟" else "هذا الكتاب غير موجود على الخادم بعد؛ سيُجلب من موقع shamela.ws صفحةً صفحة.") },
            confirmButton = { TextButton(onClick = { scope.launch { app.downloads.enqueue(b) }; confirm = null }) { Text("تنزيل") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun Entry(app: App, nav: NavHostController, b: Book, isInstalled: Boolean, isQueued: Boolean, job: org.murabbie.shamela.data.DownloadJob?, showAuthor: Boolean, onDownload: () -> Unit) {
    BookRow(
        book = b, state = bookState(isInstalled, job, b, isQueued), job = job, showAuthor = showAuthor, showCategory = false,
        onClick = { nav.go("reader/${b.id}") }, onLongClick = { nav.go("book/${b.id}") }, onDownload = onDownload,
    )
}
