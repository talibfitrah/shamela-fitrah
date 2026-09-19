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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import org.murabbie.shamela.data.JobStatus
import org.murabbie.shamela.data.NewBook
import org.murabbie.shamela.data.ScanState
import org.murabbie.shamela.ui.EmptyState
import org.murabbie.shamela.ui.SectionHeader
import org.murabbie.shamela.ui.go
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * مستجدات الشاملة: فحص موقع shamela.ws كاملًا (كل الأقسام) واستخراج الكتب التي أُضيفت إلى الموقع
 * ولم تصل إلى حزمة المكتبة بعد، مع بحث فيها وقراءة فورية أو تنزيل.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewBooksScreen(app: App, nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val scan by app.newBooks.scan.collectAsState()
    val state by app.newBooks.state.collectAsState()
    val installedVer by app.library.installedVersion.collectAsState()
    val installed = remember(installedVer) { app.library.installedIds() }
    val jobs by app.downloads.jobs.collectAsState()
    val queued by app.downloads.queued.collectAsState()
    var q by remember { mutableStateOf("") }
    val diag by org.murabbie.shamela.data.NasHttp.status.collectAsState()

    // فحص تلقائي إن لم يُفحص من قبل أو مضى يوم
    LaunchedEffect(Unit) { if (app.newBooks.stale() && state !is ScanState.Running) scope.launch { runCatching { app.newBooks.scanSite() } } }

    val list = scan?.books ?: emptyList()
    val filtered = remember(list, q) {
        val words = ArabicText.queryWords(q)
        if (words.isEmpty()) list else list.filter { b -> val n = ArabicText.normalize(b.name + " " + b.authorName + " " + b.card + " " + b.categoryName); words.all { n.contains(it.trimEnd('*')) } }
    }
    val grouped = remember(filtered) { filtered.groupBy { it.categoryName } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("مستجدات الشاملة") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } })
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("البحث عن الكتب الجديدة في shamela.ws", style = MaterialTheme.typography.titleSmall)
                        Text("يقرأ التطبيق أقسام الموقع كلها ويقارنها بفهرس المكتبة، فيُظهر كل كتاب أُضيف إلى الموقع ولم يصل إلى حزمة المكتبة بعد. تُقرأ هذه الكتب فورًا من الموقع أو تُنزَّل للاستخدام دون اتصال.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        val s = scan
                        if (s != null) {
                            Spacer(Modifier.height(8.dp))
                            val date = remember(s.scannedAt) { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale("ar")).format(Date(s.scannedAt)) }
                            Text("آخر فحص: ${ArabicText.arabicDigits(date)}", style = MaterialTheme.typography.labelMedium)
                            Text("كتب الموقع: ${ArabicText.arabicDigits(s.siteTotal.toString())} — في المكتبة: ${ArabicText.arabicDigits(s.localTotal.toString())} — الجديد: ${ArabicText.arabicDigits(s.books.size.toString())}", style = MaterialTheme.typography.labelMedium)
                        }
                        when (val st = state) {
                            is ScanState.Running -> {
                                Spacer(Modifier.height(8.dp))
                                if (st.total > 0) LinearProgressIndicator(progress = { st.done.toFloat() / st.total }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(if (st.total > 0) "فحص القسم ${ArabicText.arabicDigits(st.done.toString())} من ${ArabicText.arabicDigits(st.total.toString())} — وُجد ${ArabicText.arabicDigits(st.found.toString())}" else "قراءة أقسام الموقع…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                            }
                            is ScanState.Failed -> {
                                Spacer(Modifier.height(6.dp))
                                Text("تعذّر الفحص: ${st.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                if (diag.isNotBlank()) Text(diag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            else -> {}
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(onClick = { scope.launch { runCatching { app.newBooks.scanSite() } } }, enabled = state !is ScanState.Running) { Text(if (state is ScanState.Running) "جارٍ الفحص…" else "افحص الموقع الآن") }
                            Spacer(Modifier.width(8.dp))
                            if (list.isNotEmpty()) OutlinedButton(onClick = { scope.launch { app.downloads.enqueueAll(list.mapNotNull { app.book(it.id) }) } }) { Text("تنزيل كل المستجدات (${ArabicText.arabicDigits(list.size)})") }
                        }
                    }
                }
                if (list.isNotEmpty()) OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    placeholder = { Text("بحث في المستجدات (اسم الكتاب، المؤلف، البطاقة)") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (scan == null && state !is ScanState.Running) item { EmptyState("لم يُفحص الموقع بعد. اضغط «افحص الموقع الآن».", Modifier.height(160.dp)) }
            else if (list.isEmpty() && state !is ScanState.Running) item { EmptyState("المكتبة مطابقة للموقع: لا كتب جديدة.", Modifier.height(160.dp)) }
            else if (filtered.isEmpty() && q.isNotBlank()) item { EmptyState("لا نتائج", Modifier.height(120.dp)) }
            grouped.forEach { (cat, books) ->
                item(key = "h-$cat") { SectionHeader("$cat (${ArabicText.arabicDigits(books.size.toString())})") }
                items(books, key = { it.id }) { b -> NewBookRow(app, nav, b, b.id in installed, jobs[b.id]?.let { j -> if (j.status != JobStatus.ERROR) j.message else null } ?: if (b.id in queued) "في الطابور" else null) }
            }
        }
    }
}

@Composable
private fun NewBookRow(app: App, nav: NavHostController, b: NewBook, isInstalled: Boolean, jobText: String?) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().clickable { nav.go("book/${b.id}") }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(b.name, style = MaterialTheme.typography.titleSmall)
        Text(b.authorName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (b.card.isNotBlank()) Text(b.card, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { nav.go("reader/${b.id}") }) { Text("اقرأ") }
            when {
                isInstalled -> Text("مثبَّت", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                jobText != null -> Text(jobText, style = MaterialTheme.typography.labelMedium)
                else -> TextButton(onClick = { scope.launch { app.book(b.id)?.let { app.downloads.enqueue(it) } } }) { Text("تنزيل") }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}
