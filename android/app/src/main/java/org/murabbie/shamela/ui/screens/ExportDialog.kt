package org.murabbie.shamela.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.murabbie.shamela.App
import org.murabbie.shamela.data.ArabicText
import org.murabbie.shamela.data.Book
import org.murabbie.shamela.data.BookReader
import org.murabbie.shamela.data.ExportRequest
import org.murabbie.shamela.data.Exporter
import org.murabbie.shamela.data.Title
import org.murabbie.shamela.ui.Chip
import java.io.File

/**
 * حوار الإخراج إلى Word أو PDF: الصفحة الحالية، أو الفصل الحالي، أو نطاق صفحات، أو الكتاب كله.
 * [ids] صفحات الكتاب بالترتيب (id, part, page)، و[currentId] الصفحة المفتوحة (أو null من بطاقة الكتاب).
 */
@Composable
fun ExportDialog(app: App, book: Book, reader: BookReader, ids: List<Triple<Int, String?, Int?>>, currentId: Int?, titles: List<Title>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.state.collectAsState()
    var scopeSel by remember { mutableStateOf(if (currentId == null) 3 else 0) } // 0 الصفحة، 1 الفصل، 2 نطاق، 3 الكتاب
    var format by remember { mutableStateOf(0) } // 0 Word، 1 PDF
    var foot by remember { mutableStateOf(true) }
    var tashkeel by remember { mutableStateOf(settings.showTashkeel) }
    var breaks by remember { mutableStateOf(false) }
    val cur = remember(currentId) { ids.firstOrNull { it.first == currentId } }
    var fromPart by remember { mutableStateOf(cur?.second ?: "") }
    var fromPage by remember { mutableStateOf(cur?.third?.toString() ?: "") }
    var toPart by remember { mutableStateOf(cur?.second ?: "") }
    var toPage by remember { mutableStateOf(cur?.third?.toString() ?: "") }
    var err by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var result by remember { mutableStateOf<File?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val hasParts = remember(ids) { ids.any { !it.second.isNullOrBlank() } }

    // الفصل الحالي: من عنوانه إلى ما قبل العنوان التالي في مستواه أو أعلى
    val chapter = remember(currentId, titles) {
        if (currentId == null || titles.isEmpty()) null else {
            val i = titles.indexOfLast { it.page <= currentId }
            if (i < 0) null else {
                val t = titles[i]
                val next = (i + 1 until titles.size).firstOrNull { titles[it].level <= t.level }?.let { titles[it].page }
                val startIdx = ids.indexOfFirst { it.first >= t.page }.coerceAtLeast(0)
                val endIdx = if (next == null) ids.size else ids.indexOfFirst { it.first >= next }.let { if (it < 0) ids.size else it }
                Triple(t, startIdx, endIdx)
            }
        }
    }

    fun resolve(part: String, page: String): Int? {
        val pg = ArabicText.normalize(page).toIntOrNull() ?: return null
        val pt = ArabicText.normalize(part).takeIf { it.isNotBlank() }
        return ids.firstOrNull { (pt == null || it.second == pt) && it.third == pg }?.let { ids.indexOf(it) }
    }

    fun start() {
        err = ""
        val (pageIds, title) = when (scopeSel) {
            0 -> listOf(currentId ?: return) to "${book.shortName} — ${cur?.let { pageLabel(it) } ?: ""}"
            1 -> { val c = chapter ?: run { err = "لا فصل حاليًّا"; return }; ids.subList(c.second, c.third).map { it.first } to "${book.shortName} — ${c.first.text}" }
            2 -> {
                val a = resolve(fromPart, fromPage) ?: run { err = "صفحة البداية غير موجودة"; return }
                val b = resolve(toPart, toPage) ?: run { err = "صفحة النهاية غير موجودة"; return }
                if (b < a) { err = "النهاية قبل البداية"; return }
                ids.subList(a, b + 1).map { it.first } to "${book.shortName} — من ${pageLabel(ids[a])} إلى ${pageLabel(ids[b])}"
            }
            else -> ids.map { it.first } to book.name
        }
        if (pageIds.isEmpty()) { err = "لا صفحات"; return }
        val req = ExportRequest(book, pageIds, title, if (format == 0) ExportRequest.Format.DOCX else ExportRequest.Format.PDF, foot, tashkeel, breaks)
        progress = 0 to pageIds.size
        job = scope.launch {
            try { result = Exporter.export(ctx, reader, req) { d, t -> progress = d to t } }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; err = "تعذّر الإخراج: ${e.message}" }
            progress = null
        }
    }

    fun open(f: File, share: Boolean) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        val mime = if (f.name.endsWith(".pdf")) "application/pdf" else "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val i = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension)
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(i, if (share) "مشاركة" else "فتح بـ")) }.onFailure { err = "لا تطبيق يفتح هذا النوع" }
    }

    val r = result
    if (r != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("تمّ الإخراج") },
            text = { Column { Text(r.name, style = MaterialTheme.typography.bodyMedium); Text("${ArabicText.formatSize(r.length())} — محفوظ في مجلد التطبيق exports", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error) } },
            confirmButton = { Row { TextButton(onClick = { open(r, false) }) { Text("فتح") }; TextButton(onClick = { open(r, true) }) { Text("مشاركة") } } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        title = { Text("إخراج إلى Word / PDF") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val p = progress
                if (p != null) {
                    Text("جارٍ الإخراج… ${ArabicText.arabicDigits(p.first)} / ${ArabicText.arabicDigits(p.second)} صفحة")
                    LinearProgressIndicator(progress = { if (p.second > 0) p.first.toFloat() / p.second else 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    return@Column
                }
                Text("ما يُخرَج", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.padding(vertical = 4.dp)) {
                    if (currentId != null) { Chip("الصفحة الحالية", scopeSel == 0) { scopeSel = 0 }; Chip("الفصل الحالي", scopeSel == 1) { scopeSel = 1 } }
                    Chip("نطاق", scopeSel == 2) { scopeSel = 2 }
                    Chip("الكتاب كله", scopeSel == 3) { scopeSel = 3 }
                }
                when (scopeSel) {
                    1 -> chapter?.let { c -> Text("«${c.first.text}» — ${ArabicText.arabicDigits(c.third - c.second)} صفحة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } ?: Text("لا عناوين في هذا الكتاب", style = MaterialTheme.typography.bodySmall)
                    2 -> Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("من", Modifier.width(28.dp))
                            if (hasParts) { OutlinedTextField(fromPart, { fromPart = it }, label = { Text("الجزء") }, singleLine = true, modifier = Modifier.weight(1f)); Spacer(Modifier.width(6.dp)) }
                            OutlinedTextField(fromPage, { fromPage = it }, label = { Text("الصفحة") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Text("إلى", Modifier.width(28.dp))
                            if (hasParts) { OutlinedTextField(toPart, { toPart = it }, label = { Text("الجزء") }, singleLine = true, modifier = Modifier.weight(1f)); Spacer(Modifier.width(6.dp)) }
                            OutlinedTextField(toPage, { toPage = it }, label = { Text("الصفحة") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        Text("بأرقام صفحات المطبوع (وأرقام الأجزاء إن وُجدت)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    3 -> Text("${ArabicText.arabicDigits(ids.size)} صفحة" + (if (ids.size > 3000) " — كتاب كبير؛ قد يستغرق الإخراج دقائق" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Text("الصيغة", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.padding(vertical = 4.dp)) { Chip("Word (docx)", format == 0) { format = 0 }; Chip("PDF", format == 1) { format = 1 } }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("الهوامش", Modifier.weight(1f)); Switch(foot, { foot = it }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("التشكيل", Modifier.weight(1f)); Switch(tashkeel, { tashkeel = it }) }
                if (format == 0) Row(verticalAlignment = Alignment.CenterVertically) { Text("فاصل صفحة لكل صفحة من المطبوع", Modifier.weight(1f)); Switch(breaks, { breaks = it }) }
                if (format == 0) Text("Word: خط Traditional Arabic، متن ١٦، اتجاه من اليمين، عناوين الفصول بأنماط Heading (يظهر بها فهرس Word).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { if (progress == null) TextButton(onClick = { start() }) { Text("إخراج") } else TextButton(onClick = { job?.cancel(); progress = null }) { Text("إلغاء") } },
        dismissButton = { if (progress == null) TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}

private fun pageLabel(t: Triple<Int, String?, Int?>): String {
    val p = t.third?.let { ArabicText.arabicDigits(it) } ?: ""
    val j = t.second?.takeIf { it.isNotBlank() }?.let { ArabicText.arabicDigits(it) }
    return if (j != null && p.isNotEmpty()) "ج$j ص$p" else if (p.isNotEmpty()) "ص$p" else "صفحة ${ArabicText.arabicDigits(t.first)}"
}
