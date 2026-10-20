package org.murabbie.shamela.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.murabbie.shamela.App
import org.murabbie.shamela.data.ArabicText

/**
 * النسخ الاحتياطي للكتب واستعادتها: مجلد خارجي في ذاكرة الهاتف يبقى بعد إزالة التطبيق،
 * تُنسخ إليه الكتب تلقائيًّا عند تنزيلها، وتُستعاد منه بعد إعادة التثبيت دون تنزيل.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(app: App, nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val settings by app.settings.state.collectAsState()
    val installedVer by app.library.installedVersion.collectAsState()
    var available by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var backedUp by remember { mutableStateOf(0) }
    var backedBytes by remember { mutableStateOf(0L) }
    var restorable by remember { mutableStateOf(0) }
    var progress by remember { mutableStateOf<Triple<Int, Int, String>?>(null) }
    var result by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var confirmOff by remember { mutableStateOf(false) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }

    fun refresh() {
        refreshJob?.cancel()  // كل كتاب يُنزَّل يطلب تحديثًا: لا تتراكم الفحوص
        refreshJob = scope.launch {
            delay(300)  // تحديثات متتابعة (كتاب بعد كتاب) تُدمج في فحص واحد
            // فحص المجلد استعلام لكل ملف: خارج الخيط الرئيسي وإلا تجمّدت الشاشة مع المجلدات الكبيرة
            val a = withContext(Dispatchers.IO) { app.backup.available() }
            folderName = withContext(Dispatchers.IO) { app.backup.folderName() }
            val list = if (a) withContext(Dispatchers.IO) { app.backup.listBackedUp() } else emptyMap()
            val installed = if (a) withContext(Dispatchers.IO) { app.library.installedIds() } else emptySet()
            available = a; backedUp = list.size; backedBytes = list.values.sum(); restorable = list.keys.count { it !in installed }
        }
    }
    LaunchedEffect(settings.backupUri, installedVer) { refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { app.backup.setFolder(uri); result = ""; refresh() }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("حفظ الكتب واستعادتها") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("لماذا؟", style = MaterialTheme.typography.titleSmall)
                    Text("كتب التطبيق تُحفظ في مخزنه الخاص، والنظام يمحوه كله عند إزالة التطبيق أو مسح الهاتف. اختر هنا مجلدًا في ذاكرة الهاتف العامة (أنشئ مثلًا مجلد «Shamela» داخل Documents): كل كتاب تنزّله يُنسخ إليه تلقائيًّا مضغوطًا، وبعد إعادة تثبيت التطبيق تختار المجلد نفسه فتعود كتبك كلها دون تنزيل. والتطبيق يفحص المجلد قبل أي تنزيل: إن وجد الكتاب فيه أخذه منه فورًا بلا إنترنت. وللنقل إلى هاتف جديد انسخ المجلد إليه ثم اختره من هنا.\n\nوعند حذف التطبيق يعرض عليك النظام الآن خيار «الإبقاء على بيانات التطبيق»: إن علّمتَه بقيت الكتب والفهرس والعلامات كلها على الجهاز، وتجدها كما كانت فور إعادة التثبيت دون استعادة ولا تنزيل؛ وإن تركته حُذفت مع التطبيق — وتبقى نسخة مجلد الحفظ على كل حال.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    if (!available) {
                        Text(if (settings.backupUri.isBlank()) "لم يُختَر مجلد بعد." else "تعذّر الوصول إلى المجلد المحفوظ (أُزيل أو أُلغي إذنه) — اختر المجلد من جديد.", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { picker.launch(null) }, modifier = Modifier.padding(top = 8.dp)) { Text("اختيار مجلد الحفظ…") }
                    } else {
                        Text("المجلد: $folderName", style = MaterialTheme.typography.titleSmall)
                        Text("فيه ${ArabicText.arabicDigits(backedUp)} كتابًا (${ArabicText.formatSize(backedBytes)})" + (if (restorable > 0) " — منها ${ArabicText.arabicDigits(restorable)} غير مثبَّت على الجهاز" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                        val p = progress
                        if (p != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("${p.third} (${ArabicText.arabicDigits(p.first)}/${ArabicText.arabicDigits(p.second)})", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            LinearProgressIndicator(progress = { if (p.second > 0) p.first.toFloat() / p.second else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            TextButton(onClick = { job?.cancel(); progress = null }) { Text("إيقاف") }
                        } else {
                            if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 6.dp))
                            Spacer(Modifier.height(8.dp))
                            Row {
                                Button(onClick = {
                                    job = scope.launch {
                                        val (ok, fail) = app.backup.backupAll { d, t, n -> progress = Triple(d, t, n) }
                                        progress = null; result = "نُسخ ${ArabicText.arabicDigits(ok)} كتابًا" + (if (fail > 0) "، وتعذّر ${ArabicText.arabicDigits(fail)}" else ""); refresh()
                                    }
                                }) { Text("نسخ كل الكتب المثبَّتة") }
                                Spacer(Modifier.width(8.dp))
                                if (restorable > 0) Button(onClick = {
                                    job = scope.launch {
                                        val (ok, fail) = app.backup.restoreAll { d, t, n -> progress = Triple(d, t, n) }
                                        progress = null; result = "استُعيد ${ArabicText.arabicDigits(ok)} كتابًا" + (if (fail > 0) "، وتعذّر ${ArabicText.arabicDigits(fail)}" else ""); refresh()
                                    }
                                }) { Text("استعادة الكتب (${ArabicText.arabicDigits(restorable)})") }
                            }
                            Row(Modifier.padding(top = 4.dp)) {
                                OutlinedButton(onClick = { picker.launch(null) }) { Text("تغيير المجلد") }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { confirmOff = true }) { Text("إيقاف الحفظ التلقائي") }
                            }
                        }
                    }
                }
            }
            Text("الكتب الجديدة تُنسخ إلى المجلد تلقائيًّا فور تنزيلها ما دام المجلد مختارًا. حذف كتاب من التطبيق لا يحذفه من المجلد.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        }
    }
    if (confirmOff) AlertDialog(onDismissRequest = { confirmOff = false }, title = { Text("إيقاف الحفظ التلقائي") },
        text = { Text("يتوقف نسخ الكتب الجديدة إلى المجلد، وتبقى الكتب المنسوخة فيه كما هي.") },
        confirmButton = { TextButton(onClick = { app.backup.clearFolder(); confirmOff = false; refresh() }) { Text("إيقاف") } },
        dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("إلغاء") } })
}
