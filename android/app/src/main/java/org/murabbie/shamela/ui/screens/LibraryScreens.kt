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
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.murabbie.shamela.App
import org.murabbie.shamela.data.ArabicText
import org.murabbie.shamela.data.Author
import org.murabbie.shamela.data.Book
import org.murabbie.shamela.data.Category
import org.murabbie.shamela.ui.BookRow
import org.murabbie.shamela.ui.EmptyState
import org.murabbie.shamela.ui.Loading
import org.murabbie.shamela.ui.SearchField
import org.murabbie.shamela.ui.bookState
import org.murabbie.shamela.ui.go

private val libraryTabs = listOf("الأقسام", "المؤلفون", "كل الكتب", "المثبَّتة", "المضاف حديثًا")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(app: App, nav: NavHostController) {
    val settings by app.settings.state.collectAsState()
    var tab by remember { mutableStateOf(settings.libraryTab.coerceIn(0, libraryTabs.size - 1)) }
    val manifest by app.library.manifest.collectAsState()
    val dataUpdate = app.library.dataUpdateAvailable()
    val appUpdate = (manifest?.appVersionCode ?: 0) > org.murabbie.shamela.BuildConfig.VERSION_CODE

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("المكتبة الشاملة") }, actions = {
            if (dataUpdate || appUpdate) IconButton(onClick = { nav.go("updates") }) { Icon(Icons.Default.Update, contentDescription = "تحديث", tint = MaterialTheme.colorScheme.secondary) }
        })
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            libraryTabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i; app.settings.update { it.copy(libraryTab = i) } }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> CategoriesTab(app, nav)
            1 -> AuthorsTab(app, nav)
            2 -> AllBooksTab(app, nav)
            3 -> InstalledTab(app, nav)
            4 -> RecentTab(app, nav)
        }
    }
}

@Composable
private fun CategoriesTab(app: App, nav: NavHostController) {
    var cats by remember { mutableStateOf<List<Category>?>(null) }
    LaunchedEffect(Unit) { cats = app.library.categories() }
    val list = cats ?: return Loading()
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { c ->
            Row(Modifier.fillMaxWidth().clickable { nav.go("category/${c.id}") }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(ArabicText.arabicDigits(c.booksCount) + " كتاب", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            // الأقسام الخاصة بالأعلام: بعد «مسائل فقهية» وقبل «السياسة الشرعية والقضاء»
            if (c.id == org.murabbie.shamela.data.Scholars.AFTER_CATEGORY) ScholarRows(app, nav)
        }
    }
}

@Composable
private fun AuthorsTab(app: App, nav: NavHostController) {
    var q by rememberSaveable { mutableStateOf("") }
    var authors by remember { mutableStateOf<List<Author>?>(null) }
    LaunchedEffect(q) { authors = app.library.authors(q) }
    Column(Modifier.fillMaxSize()) {
        SearchField(q, { q = it }, "بحث في أسماء المؤلفين")
        val list = authors ?: return Loading()
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { a ->
                Row(Modifier.fillMaxWidth().clickable { nav.go("author/${a.id}") }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall)
                        if (a.deathLabel.isNotEmpty()) Text(a.deathLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(ArabicText.arabicDigits(a.booksCount), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
fun BookList(app: App, nav: NavHostController, books: List<Book>, showCategory: Boolean = false, header: (@Composable () -> Unit)? = null) {
    val jobs by app.downloads.jobs.collectAsState()
    val queued by app.downloads.queued.collectAsState()
    val installedVer by app.library.installedVersion.collectAsState()
    val installed = remember(installedVer) { app.library.installedIds() }
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<Book?>(null) }
    if (books.isEmpty()) { EmptyState("لا كتب هنا"); return }
    LazyColumn(Modifier.fillMaxSize()) {
        if (header != null) item { header() }
        items(books, key = { it.id }) { b ->
            val job = jobs[b.id]
            BookRow(
                book = b, state = bookState(b.id in installed, job, b, b.id in queued), job = job, showCategory = showCategory,
                onClick = { nav.go("reader/${b.id}") },
                onLongClick = { nav.go("book/${b.id}") },
                onDownload = { confirm = b },
            )
        }
    }
    confirm?.let { b ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(b.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            text = { Text(if (b.file != null && b.file.pack.isNotBlank()) "تنزيل الكتاب من الخادم (${ArabicText.formatSize(b.file.length)}؛ ${ArabicText.formatSize(b.file.size)} بعد فك الضغط)؟" else "هذا الكتاب غير موجود على الخادم بعد؛ سيُجلب من موقع shamela.ws صفحةً صفحة.") },
            confirmButton = { TextButton(onClick = { scope.launch { app.downloads.enqueue(b) }; confirm = null }) { Text("تنزيل") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun AllBooksTab(app: App, nav: NavHostController) {
    var q by rememberSaveable { mutableStateOf("") }
    var books by remember { mutableStateOf<List<Book>?>(null) }
    LaunchedEffect(q) { books = if (q.isBlank()) app.library.allBooks() else app.library.searchBooks(q) }
    Column(Modifier.fillMaxSize()) {
        SearchField(q, { q = it }, "بحث في أسماء الكتب والمؤلفين")
        val list = books ?: return Loading()
        Text("${ArabicText.arabicDigits(list.size)} كتاب", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        BookList(app, nav, list, showCategory = true)
    }
}

@Composable
private fun InstalledTab(app: App, nav: NavHostController) {
    val installedVer by app.library.installedVersion.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    var books by remember { mutableStateOf<List<Book>?>(null) }
    LaunchedEffect(installedVer, q) {
        val ids = app.library.installedIds()
        val all = app.library.booksByIds(ids) + app.userDb.extraBooks().filter { it.id in ids }
        val words = ArabicText.queryWords(q)
        books = if (words.isEmpty()) all.sortedBy { it.name } else all.filter { b -> val n = ArabicText.normalize(b.name + " " + b.authorName); words.all { n.contains(it.trimEnd('*')) } }
    }
    Column(Modifier.fillMaxSize()) {
        SearchField(q, { q = it }, "بحث في الكتب المثبَّتة")
        val list = books ?: return Loading()
        val size = remember(installedVer) { app.library.installedSize() }
        Text("${ArabicText.arabicDigits(list.size)} كتاب مثبَّت — ${ArabicText.formatSize(size)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        if (list.isEmpty()) EmptyState("لم تنزّل كتبًا بعد.\nتصفح الأقسام أو المؤلفين واضغط أيقونة التنزيل بجانب الكتاب.") else BookList(app, nav, list, showCategory = true)
    }
}

@Composable
private fun RecentTab(app: App, nav: NavHostController) {
    var books by remember { mutableStateOf<List<Book>?>(null) }
    LaunchedEffect(Unit) { books = app.library.recentBooks(300) }
    Column(Modifier.fillMaxSize()) {
        val scan by app.newBooks.scan.collectAsState()
        Card(Modifier.fillMaxWidth().padding(12.dp).clickable { nav.go("newbooks") }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("مستجدات موقع الشاملة", style = MaterialTheme.typography.titleSmall)
                    Text(scan?.let { "كتب جديدة في shamela.ws لم تصل إلى الحزمة: ${ArabicText.arabicDigits(it.books.size)}" } ?: "البحث في الموقع عن الكتب التي أُضيفت بعد الحزمة وقراءتها فورًا", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val list = books ?: return Loading()
        BookList(app, nav, list, showCategory = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryBooksScreen(app: App, nav: NavHostController, id: Int) {
    var cat by remember { mutableStateOf<Category?>(null) }
    var books by remember { mutableStateOf<List<Book>?>(null) }
    var q by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var confirmAll by remember { mutableStateOf(false) }
    LaunchedEffect(id) { cat = app.library.category(id); books = app.library.booksInCategory(id) }
    val list = books
    val filtered = remember(list, q) {
        val words = ArabicText.queryWords(q)
        if (list == null || words.isEmpty()) list else list.filter { b -> val n = ArabicText.normalize(b.name + " " + b.authorName); words.all { n.contains(it.trimEnd('*')) } }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(cat?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } },
            actions = { IconButton(onClick = { confirmAll = true }) { Icon(Icons.Default.CloudDownload, contentDescription = "تنزيل القسم كاملًا") } },
        )
        SearchField(q, { q = it }, "بحث داخل القسم")
        if (filtered == null) Loading() else {
            Text("${ArabicText.arabicDigits(filtered.size)} كتاب", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            BookList(app, nav, filtered)
        }
    }
    if (confirmAll && list != null) {
        val total = list.sumOf { it.file?.length ?: 0L }
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("تنزيل القسم كاملًا") },
            text = { Text("تنزيل ${ArabicText.arabicDigits(list.size)} كتابًا (${ArabicText.formatSize(total)} تنزيلًا، ${ArabicText.formatSize(list.sumOf { it.file?.size ?: 0L })} على الجهاز)؟") },
            confirmButton = { TextButton(onClick = { scope.launch { app.downloads.enqueueAll(list) }; confirmAll = false }) { Text("تنزيل") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("إلغاء") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorBooksScreen(app: App, nav: NavHostController, id: Int) {
    var author by remember { mutableStateOf<Author?>(null) }
    var books by remember { mutableStateOf<List<Book>?>(null) }
    val scope = rememberCoroutineScope()
    var confirmAll by remember { mutableStateOf(false) }
    LaunchedEffect(id) { author = app.library.author(id); books = app.library.booksByAuthor(id) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Column { Text(author?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis); author?.deathLabel?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.labelMedium) } } },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } },
            actions = { IconButton(onClick = { confirmAll = true }) { Icon(Icons.Default.CloudDownload, contentDescription = "تنزيل كتب المؤلف") } },
        )
        val list = books ?: return Loading()
        BookList(app, nav, list, showCategory = true)
    }
    val list = books
    if (confirmAll && list != null) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("تنزيل كل كتب المؤلف") },
            text = { Text("تنزيل ${ArabicText.arabicDigits(list.size)} كتابًا (${ArabicText.formatSize(list.sumOf { it.file?.length ?: 0L })})؟") },
            confirmButton = { TextButton(onClick = { scope.launch { app.downloads.enqueueAll(list) }; confirmAll = false }) { Text("تنزيل") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("إلغاء") } },
        )
    }
}
