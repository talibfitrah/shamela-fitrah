package org.murabbie.shamela.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import org.murabbie.shamela.ui.EmptyState
import org.murabbie.shamela.ui.go

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(app: App, nav: NavHostController) {
    val jobs by app.downloads.jobs.collectAsState()
    val bulk by app.downloads.bulk.collectAsState()
    val settings by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val list = jobs.values.sortedBy { it.order }
    var confirmAll by remember { mutableStateOf(false) }
    val busy = bulk.pendingTotal > 0 && !bulk.isPaused
    val installedVer by app.library.installedVersion.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("التنزيلات") }, actions = {
            if (bulk.done > 0) TextButton(onClick = { app.downloads.clearFinished() }) { Text("مسح المكتمل") }
            if (busy) TextButton(onClick = { app.downloads.pauseAll() }) { Text("إيقاف مؤقت") }
            else if (bulk.isPaused || bulk.paused > 0 || bulk.error > 0) TextButton(onClick = { app.downloads.resumeAll() }) { Text("استئناف الكل") }
        })
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("المساحة الحرة: ${ArabicText.formatSize(app.library.freeSpace())} · المثبَّت: ${ArabicText.formatSize(remember(installedVer) { app.library.installedSize() })}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { confirmAll = true }) { Text("تنزيل المكتبة كاملة") }
        }
        if (bulk.pendingTotal > 0 || bulk.done > 0 || bulk.error > 0) Text(
            buildString {
                if (bulk.pendingTotal > 0) append("في الطابور: ${ArabicText.arabicDigits(bulk.pendingTotal)}")
                if (bulk.done > 0) { if (isNotEmpty()) append(" · "); append("تمّ: ${ArabicText.arabicDigits(bulk.done)}") }
                if (bulk.error > 0) { if (isNotEmpty()) append(" · "); append("تعذّر: ${ArabicText.arabicDigits(bulk.error)}") }
            },
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
        // مستجدات الموقع (shamela.ws/#recent وما بعده): تنزيل خاص
        val scan by app.newBooks.scan.collectAsState()
        val installedIds = remember(installedVer) { app.library.installedIds() }
        val fresh = remember(scan, installedIds) { scan?.books?.filter { it.id !in installedIds } ?: emptyList() }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { nav.go("newbooks") }) {
                Text("مستجدات موقع الشاملة", style = MaterialTheme.typography.labelLarge)
                Text(if (scan == null) "لم يُفحص الموقع بعد — اضغط للفحص" else if (fresh.isEmpty()) "لا كتب جديدة غير منزَّلة" else "${ArabicText.arabicDigits(fresh.size)} كتابًا جديدًا لم يُنزَّل بعد", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (fresh.isNotEmpty()) TextButton(onClick = { scope.launch { app.downloads.enqueueAll(fresh.mapNotNull { app.book(it.id) }) } }) { Text("تنزيل المستجدات") }
            else TextButton(onClick = { nav.go("newbooks") }) { Text("فحص الموقع") }
        }
        if (settings.wifiOnlyDownloads) Text("التنزيل عبر Wi-Fi فقط (من الإعدادات)", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
        if (list.isEmpty()) EmptyState("لا تنزيلات جارية.\nمن المكتبة اضغط أيقونة التنزيل بجانب أي كتاب، أو نزّل قسمًا كاملًا من أيقونة السحابة في صفحة القسم.")
        else LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.bookId }) { j ->
                Column(Modifier.fillMaxWidth().clickable { nav.go(if (j.status == JobStatus.DONE) "reader/${j.bookId}" else "book/${j.bookId}") }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(j.bookName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val st = when (j.status) {
                                JobStatus.QUEUED -> "في الانتظار" + if (j.message.isNotBlank()) " — ${j.message}" else ""
                                JobStatus.DOWNLOADING -> if (j.total > 0) "${ArabicText.formatSize(j.bytes)} / ${ArabicText.formatSize(j.total)}" + (if (j.message.isNotBlank()) " — ${j.message}" else "") else j.message
                                JobStatus.INSTALLING -> j.message
                                JobStatus.DONE -> "اكتمل — اضغط للقراءة"
                                JobStatus.ERROR -> "تعذر: ${j.message}"
                                JobStatus.PAUSED -> "متوقف مؤقتًا"
                            }
                            Text(st, style = MaterialTheme.typography.bodySmall, color = if (j.status == JobStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (j.status == JobStatus.ERROR || j.status == JobStatus.PAUSED) IconButton(onClick = { scope.launch { app.book(j.bookId)?.let { app.downloads.retry(it) } } }) { Icon(Icons.Default.Refresh, contentDescription = "إعادة") }
                        if (j.status != JobStatus.DONE) IconButton(onClick = { app.downloads.cancel(j.bookId) }) { Icon(Icons.Default.Close, contentDescription = "إلغاء") }
                    }
                    if (j.status == JobStatus.DOWNLOADING || j.status == JobStatus.INSTALLING) {
                        Spacer(Modifier.height(6.dp))
                        val p = if (j.total > 0) (j.bytes.toFloat() / j.total).coerceIn(0f, 1f) else null
                        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
    if (confirmAll) {
        var stats by remember { mutableStateOf<Map<String, Long>?>(null) }
        androidx.compose.runtime.LaunchedEffect(Unit) { stats = app.library.stats() }
        val onDevice = stats?.get("size") ?: 0L
        val free = app.library.freeSpace()
        val enough = free < 0 || free >= onDevice + (500L shl 20)
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("تنزيل المكتبة كاملة") },
            text = {
                Column {
                    Text("سيُنزَّل كل كتب المكتبة (${ArabicText.arabicDigits(stats?.get("books") ?: 0L)} كتاب) ويُقرأ ويُبحث فيه دون اتصال.")
                    Spacer(Modifier.height(8.dp))
                    Text("التنزيل: نحو ${ArabicText.formatSize(stats?.get("download") ?: 0L)}؛ والحجم على الجهاز بعد فك الضغط: نحو ${ArabicText.formatSize(onDevice)}.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text("المساحة الحرة عندك: ${ArabicText.formatSize(free)}", style = MaterialTheme.typography.bodySmall, color = if (enough) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    if (!enough) Text("المساحة قد لا تكفي — سيتوقف التنزيل عند امتلائها ويكمل ما نزّله. الأفضل تفريغ مساحة أو التنزيل على دفعات.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("يمكن الإيقاف والاستئناف في أي وقت، ويُستأنف تلقائيًا عند انقطاع الشبكة.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { scope.launch { app.downloads.enqueueAll(app.library.allBooks().filter { it.hasFile }) }; confirmAll = false }) { Text(if (enough) "تنزيل الكل" else "تنزيل الكل رغم ذلك") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("إلغاء") } },
        )
    }
}
