package org.murabbie.shamela.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.murabbie.shamela.App
import org.murabbie.shamela.data.ArabicText
import org.murabbie.shamela.data.Book
import org.murabbie.shamela.data.BookReader
import org.murabbie.shamela.data.BookStore
import org.murabbie.shamela.data.DownloadJob
import org.murabbie.shamela.data.JobSource
import org.murabbie.shamela.data.JobStatus
import org.murabbie.shamela.data.Title
import org.murabbie.shamela.ui.KeyValue
import org.murabbie.shamela.ui.Loading
import org.murabbie.shamela.ui.SectionHeader
import org.murabbie.shamela.ui.go

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(app: App, nav: NavHostController, id: Int) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var book by remember { mutableStateOf<Book?>(null) }
    var notFound by remember { mutableStateOf(false) }
    val installedVer by app.library.installedVersion.collectAsState()
    val installed = remember(installedVer) { app.library.isInstalled(id) }
    val jobs by app.downloads.jobs.collectAsState()
    val queued by app.downloads.queued.collectAsState()
    val job = jobs[id] ?: if (id in queued) DownloadJob(id, "", JobSource.NAS, JobStatus.QUEUED) else null
    var titles by remember { mutableStateOf<List<Title>>(emptyList()) }
    var editions by remember { mutableStateOf<List<Book>>(emptyList()) }
    var favorite by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(setOf<Int>()) }

    LaunchedEffect(id, installedVer) {
        val b = app.book(id)
        book = b
        if (b == null) { notFound = true; return@LaunchedEffect }
        favorite = app.userDb.isFavorite(id)
        position = app.userDb.position(id)
        editions = b.groupId?.let { app.library.booksInGroup(it).filter { e -> e.id != b.id } } ?: emptyList()
        titles = if (installed) runCatching { BookReader(id, app.library.bookFile(id)).titles() }.getOrDefault(emptyList()) else emptyList()
    }

    val b = book
    if (b == null) {
        if (notFound) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("الكتاب رقم ${ArabicText.arabicDigits(id)} ليس في الفهرس الحالي.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("قد يكون من المستجدات؛ يمكن جلبه مباشرة من موقع الشاملة.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { scope.launch { app.downloads.enqueue(Book(id, "كتاب $id", 0, 1, 0, emptyList(), 0, "", 0, null, null, null, null)); nav.go("downloads") } }) { Text("جلب من shamela.ws") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { nav.popBackStack() }) { Text("رجوع") }
            }
        } else Loading()
        return
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(b.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } },
            actions = {
                IconButton(onClick = { scope.launch { favorite = app.userDb.toggleFavorite(id) } }) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "مفضلة", tint = if (favorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                if (installed) {
                    IconButton(onClick = { nav.go("search/$id") }) { Icon(Icons.Default.Search, contentDescription = "بحث في الكتاب") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "حذف") }
                }
            },
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(vertical = 8.dp)) {
                    KeyValue("المؤلف", b.authorName)
                    if (b.authorIds.isNotEmpty() && b.mainAuthor > 0) Text("صفحة المؤلف", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp).clickable { nav.go("author/${b.mainAuthor}") })
                    KeyValue("القسم", b.categoryName)
                    KeyValue("الطبعة", b.edition)
                    KeyValue("الصفحات", if (b.pagesCount > 0) ArabicText.arabicDigits(b.pagesCount) else "")
                    KeyValue("الحجم", b.file?.takeIf { it.pack.isNotBlank() }?.let { "${ArabicText.formatSize(it.length)} تنزيلًا — ${ArabicText.formatSize(it.size)} على الجهاز" } ?: "")
                    KeyValue("أُضيف", b.addedDate)
                    KeyValue("الرقم", ArabicText.arabicDigits(b.id))
                    val card = b.metaJson?.optString("card")?.takeIf { it.isNotBlank() }
                    if (card != null) Text(card, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                    if (b.printed == 3) Text("ترقيم الكتاب غير موافق للمطبوع", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // القراءة متاحة دائمًا — إن لم يكن الكتاب مثبَّتًا يُجلب فورًا عند الفتح
                    Button(onClick = { nav.go(if (position != null) "reader/$id/$position" else "reader/$id") }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.MenuBook, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(if (position != null) "متابعة القراءة" else "اقرأ")
                    }
                    if (installed && position != null) OutlinedButton(onClick = { nav.go("reader/$id") }) { Text("من البداية") }
                }
                if (!installed) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (job != null && job.status != JobStatus.ERROR && job.status != JobStatus.DONE) {
                        Column(Modifier.weight(1f)) {
                            val p = if (job.total > 0) (job.bytes.toFloat() / job.total).coerceIn(0f, 1f) else null
                            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(when (job.status) { JobStatus.QUEUED -> "في الانتظار… " + job.message; JobStatus.INSTALLING -> job.message; JobStatus.PAUSED -> "متوقف مؤقتًا"; else -> if (job.total > 0) "${ArabicText.formatSize(job.bytes)} / ${ArabicText.formatSize(job.total)}" else job.message }, style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = { app.downloads.cancel(id) }) { Text("إلغاء") }
                    } else {
                        OutlinedButton(onClick = { scope.launch { app.downloads.enqueue(b) } }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Download, contentDescription = null); Spacer(Modifier.width(6.dp))
                            Text(if (b.file != null && b.file.pack.isNotBlank()) "تنزيل للاستخدام دون اتصال (${ArabicText.formatSize(b.file.length)})" else "تنزيل للاستخدام دون اتصال")
                        }
                    }
                }
                if (installed) {
                    var showExport by remember { mutableStateOf(false) }
                    var expIds by remember { mutableStateOf<List<Triple<Int, String?, Int?>>>(emptyList()) }
                    TextButton(onClick = { scope.launch { val rd = BookReader(id, app.library.bookFile(id)); expIds = rd.pageIndex(); if (titles.isEmpty()) titles = rd.titles(); showExport = true } }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("إخراج الكتاب أو جزء منه إلى Word / PDF") }
                    if (showExport) ExportDialog(app, b, BookReader(id, app.library.bookFile(id)), expIds, null, titles, onDismiss = { showExport = false })
                }
                if (job?.status == JobStatus.ERROR) Text("تعذر التنزيل: ${job.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                val pdfs = b.pdfUrls
                if (pdfs.isNotEmpty()) {
                    SectionHeader("النسخة المصوَّرة (PDF)")
                    pdfs.forEachIndexed { i, u ->
                        Text("ملف ${ArabicText.arabicDigits(i + 1)}: ${u.substringAfterLast('/')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } }.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                }
                if (editions.isNotEmpty()) {
                    SectionHeader("طبعات أخرى")
                    editions.forEach { e ->
                        Text(e.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().clickable { nav.go("book/${e.id}") }.padding(horizontal = 16.dp, vertical = 8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
                Text("صفحة الكتاب في shamela.ws", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp).clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shamela.ws/book/$id"))) } })
                if (installed) SectionHeader("فهرس الموضوعات" + if (titles.isNotEmpty()) " (${ArabicText.arabicDigits(titles.size)})" else "")
            }
            if (installed) {
                val visible = titles.filter { t -> !isHiddenByCollapse(t, titles, collapsed) }
                items(visible, key = { it.id }) { t ->
                    TocRow(t, collapsed = t.id in collapsed, onToggle = { collapsed = if (t.id in collapsed) collapsed - t.id else collapsed + t.id }) { nav.go("reader/$id/${t.page}") }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("حذف الكتاب من الجهاز") },
        text = { Text("سيُحذف ملف الكتاب ويمكن تنزيله مرة أخرى في أي وقت. العلامات والتعليقات تبقى.") },
        confirmButton = { TextButton(onClick = { scope.launch { app.library.deleteBook(id) }; confirmDelete = false }) { Text("حذف") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } },
    )
}

private fun isHiddenByCollapse(t: Title, all: List<Title>, collapsed: Set<Int>): Boolean {
    if (collapsed.isEmpty()) return false
    var p = t.parent
    var guard = 0
    val byId = all.associateBy { it.id }
    while (p != 0 && guard++ < 30) {
        if (p in collapsed) return true
        p = byId[p]?.parent ?: 0
    }
    return false
}

@Composable
fun TocRow(t: Title, collapsed: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = (12 + t.level * 16).dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (t.hasChildren) Text(if (collapsed) "▸" else "▾", modifier = Modifier.clickable(onClick = onToggle).padding(horizontal = 6.dp), color = MaterialTheme.colorScheme.primary)
        else Spacer(Modifier.width(18.dp))
        Text(t.text, style = if (t.level == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}
