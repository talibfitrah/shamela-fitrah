package org.murabbie.shamela.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.murabbie.shamela.App
import org.murabbie.shamela.data.ArabicText
import org.murabbie.shamela.data.Book
import org.murabbie.shamela.data.FileImport
import org.murabbie.shamela.ui.EmptyState
import org.murabbie.shamela.ui.SectionHeader
import org.murabbie.shamela.ui.go

/**
 * إدراج كتب جديدة من ملفات على الجهاز (Word / PDF نصّي / TXT / EPUB) — مثل مصادر الشاملة التي تُنزَّل لنسخة ويندوز —
 * وإدارتها (قائمة الكتب المدرَجة وحذفها).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(app: App, nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val installedVer by app.library.installedVersion.collectAsState()
    var picked by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var mime by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Triple<Int, Int, String>?>(null) }
    var error by remember { mutableStateOf("") }
    var imported by remember { mutableStateOf<List<Book>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf<Book?>(null) }
    LaunchedEffect(installedVer) { imported = app.userDb.extraBooks().filter { it.id >= FileImport.FIRST_ID } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        var fname = "book"
        runCatching { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) fname = c.getString(0) ?: fname } }
        mime = ctx.contentResolver.getType(uri)
        picked = uri to fname
        name = fname.substringBeforeLast('.')
        author = ""
        error = ""
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("إدراج كتب من ملفات") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } })
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("كتاب جديد من ملف", style = MaterialTheme.typography.titleSmall)
                        Text("اختر ملف Word (docx) أو PDF نصّي أو TXT أو EPUB — مثل الكتب التي تُنزَّل من مصادر الشاملة لنسخة ويندوز — فيُبنى منه كتاب بصيغة كتب المكتبة: يُقرأ ويُبحث فيه ويُشكَّل ويُقرأ صوتيًّا ويُخرَج. عناوين Word (Heading) تصير فهرس الكتاب، وفواصل الصفحات تصير صفحاته. ملفات PDF المصوَّرة (بلا نص) لا يمكن إدراجها إلا بعد تعرّف ضوئي.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(10.dp))
                        val p = progress
                        if (p != null) {
                            Text(p.third, style = MaterialTheme.typography.bodySmall)
                            if (p.second > 0) LinearProgressIndicator(progress = { p.first.toFloat() / p.second }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                        } else {
                            val pk = picked
                            if (pk == null) Button(onClick = { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/pdf", "text/plain", "application/epub+zip", "application/octet-stream")) }) { Text("اختيار ملف…") }
                            else {
                                Text("الملف: ${pk.second}", style = MaterialTheme.typography.bodyMedium)
                                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم الكتاب") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("المؤلف (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                                Row(Modifier.padding(top = 8.dp)) {
                                    Button(onClick = {
                                        progress = Triple(0, 0, "بدء…")
                                        scope.launch {
                                            try {
                                                val id = FileImport.importBook(ctx, app.library, app.userDb, pk.first, pk.second, mime, name, author) { d, t, m -> progress = Triple(d, t, m) }
                                                runCatching { app.backup.mirrorBook(id) }
                                                progress = null; picked = null
                                                nav.go("reader/$id")
                                            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; progress = null; error = e.message ?: e.javaClass.simpleName }
                                        }
                                    }) { Text("إدراج الكتاب") }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { picked = null; error = "" }) { Text("إلغاء") }
                                }
                            }
                            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                SectionHeader("الكتب المدرَجة (${ArabicText.arabicDigits(imported.size)})")
            }
            if (imported.isEmpty()) item { EmptyState("لا كتب مدرَجة بعد.", Modifier.height(120.dp)) }
            items(imported, key = { it.id }) { b ->
                Row(Modifier.fillMaxWidth().clickable { nav.go("reader/${b.id}") }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(b.name, style = MaterialTheme.typography.titleSmall)
                        Text(listOf(b.authorName, b.file?.pages?.takeIf { it > 0 }?.let { "${ArabicText.arabicDigits(it)} صفحة" } ?: "").filter { it.isNotBlank() }.joinToString(" — "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { confirmDelete = b }) { Text("حذف") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
    confirmDelete?.let { b ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("حذف الكتاب المدرَج") }, text = { Text(b.name) },
            confirmButton = { TextButton(onClick = { scope.launch { app.library.deleteBook(b.id); app.userDb.removeExtraBook(b.id); app.library.bumpInstalled() }; confirmDelete = null }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("إلغاء") } })
    }
}
