package org.murabbie.shamela.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import org.murabbie.shamela.BuildConfig
import org.murabbie.shamela.data.ArabicText
import org.murabbie.shamela.data.Book
import org.murabbie.shamela.data.LibraryState
import org.murabbie.shamela.data.RecentBook
import org.murabbie.shamela.data.ShamelaWeb
import org.murabbie.shamela.ui.Chip
import org.murabbie.shamela.ui.EmptyState
import org.murabbie.shamela.ui.KeyValue
import org.murabbie.shamela.ui.Loading
import org.murabbie.shamela.ui.SectionHeader
import org.murabbie.shamela.ui.go

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(app: App, nav: NavHostController) {
    val manifest by app.library.manifest.collectAsState()
    val appUpdate = (manifest?.appVersionCode ?: 0) > BuildConfig.VERSION_CODE
    val dataUpdate = app.library.dataUpdateAvailable()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("المزيد") })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            MoreItem(Icons.Default.SettingsBackupRestore, "حفظ الكتب واستعادتها", "مجلد خارجي يبقى بعد إزالة التطبيق — استعادة الكتب دون تنزيل") { nav.go("backup") }
            MoreItem(Icons.Default.NoteAdd, "إدراج كتب من ملفات", "Word، PDF نصّي، TXT، EPUB — من مصادر الشاملة أو من ملفاتك") { nav.go("import") }
            MoreItem(Icons.Default.Hub, "خدمات الشاملة", "القرآن والتفسير، تراجم الرواة، تخريج الحديث والأسانيد، البحث بالجذر") { nav.go("services") }
            MoreItem(Icons.Default.CloudDownload, "مستجدات الشاملة", "البحث في shamela.ws عن الكتب الجديدة التي لم تصل إلى الحزمة") { nav.go("newbooks") }
            MoreItem(Icons.Default.Update, "المستجدات والتحديثات", (if (appUpdate) "يتوفر إصدار جديد من التطبيق" else if (dataUpdate) "يتوفر فهرس محدَّث" else "آخر الكتب المضافة في shamela.ws وتحديث الفهرس")) { nav.go("updates") }
            MoreItem(Icons.Default.Settings, "الإعدادات", "الخادم، التنزيل، العرض") { nav.go("settings") }
            MoreItem(Icons.Default.Info, "عن التطبيق والإحصائيات", "الإصدار ${BuildConfig.VERSION_NAME}") { nav.go("about") }
        }
    }
}

@Composable
private fun MoreItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column { Text(title, style = MaterialTheme.typography.titleSmall); Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: App, nav: NavHostController) {
    val s by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(s.manifestUrl) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("الإعدادات") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionHeader("العرض")
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text("المظهر:", Modifier.width(70.dp)); listOf("النظام", "فاتح", "داكن", "ورقي").forEachIndexed { i, n -> Chip(n, s.themeMode == i) { app.settings.update { it.copy(themeMode = i) } } } }
            Text("حجم خط الواجهة: ${ArabicText.arabicDigits((s.fontScale * 100).toInt())}٪", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Slider(value = s.fontScale, onValueChange = { v -> app.settings.update { it.copy(fontScale = v) } }, valueRange = 0.8f..1.5f, modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow("إبقاء الشاشة مضاءة أثناء القراءة", s.keepScreenOn) { app.settings.update { it.copy(keepScreenOn = !it.keepScreenOn) } }
            SwitchRow("إظهار التشكيل", s.showTashkeel) { app.settings.update { it.copy(showTashkeel = !it.showTashkeel) } }
            SwitchRow("إظهار الهوامش", s.showFootnotes) { app.settings.update { it.copy(showFootnotes = !it.showFootnotes) } }

            SectionHeader("التنزيل")
            SwitchRow("التنزيل عبر Wi-Fi فقط", s.wifiOnlyDownloads) { app.settings.update { it.copy(wifiOnlyDownloads = !it.wifiOnlyDownloads) } }
            SwitchRow("فحص التحديثات تلقائيًا (يوميًا)", s.autoUpdateCheck) { app.settings.update { it.copy(autoUpdateCheck = !it.autoUpdateCheck) } }
            SwitchRow("تخزين الكتب في الذاكرة الخارجية (إن وُجدت)", s.storageExternal) { app.settings.update { it.copy(storageExternal = !it.storageExternal) }; scope.launch { app.library.check() } }
            Text("مجلد الكتب: ${app.library.booksDir.path}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            Text("المساحة الحرة: ${ArabicText.formatSize(app.library.freeSpace())} · المثبَّت: ${ArabicText.formatSize(app.library.installedSize())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            TextButton(onClick = { confirmDeleteAll = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("حذف كل الكتب المثبَّتة", color = MaterialTheme.colorScheme.error) }

            SectionHeader("الخادم (NAS)")
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("رابط manifest.json") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(onClick = { app.settings.update { it.copy(manifestUrl = url.trim()) }; scope.launch { runCatching { app.library.fetchManifest() } } }) { Text("حفظ") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { url = BuildConfig.DEFAULT_MANIFEST_URL; app.settings.update { it.copy(manifestUrl = BuildConfig.DEFAULT_MANIFEST_URL) } }) { Text("الافتراضي") }
            }
            Text("إصدار البيانات المثبَّت: ${s.dataVersion.ifBlank { "—" }}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
            TextButton(onClick = { scope.launch { app.library.installMaster() } }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("إعادة تنزيل فهرس المكتبة") }
        }
    }
    if (confirmDeleteAll) AlertDialog(
        onDismissRequest = { confirmDeleteAll = false },
        title = { Text("حذف كل الكتب") },
        text = { Text("سيُحذف كل ما نزّلته من كتب من الجهاز (يبقى الفهرس والعلامات). هل تريد المتابعة؟") },
        confirmButton = { TextButton(onClick = { scope.launch { app.library.deleteAllBooks() }; confirmDeleteAll = false }) { Text("حذف الكل", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("إلغاء") } },
    )
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** المستجدات: تحديث التطبيق والفهرس من NAS، وآخر الكتب المضافة في shamela.ws */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(app: App, nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val manifest by app.library.manifest.collectAsState()
    val libState by app.library.state.collectAsState()
    val settings by app.settings.state.collectAsState()
    var recent by remember { mutableStateOf<List<RecentBook>?>(null) }
    var recentError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val installedVer by app.library.installedVersion.collectAsState()
    val installed = remember(installedVer) { app.library.installedIds() }
    var inIndex by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val jobs by app.downloads.jobs.collectAsState()

    LaunchedEffect(Unit) {
        checking = true
        runCatching { app.library.fetchManifest() }
        checking = false
        runCatching { ShamelaWeb.recent() }.onSuccess { list ->
            recent = list
            inIndex = list.mapNotNull { app.library.book(it.id)?.id }.toSet()
        }.onFailure { recentError = it.message }
    }

    val appUpdate = manifest != null && manifest!!.appVersionCode > BuildConfig.VERSION_CODE
    val dataUpdate = app.library.dataUpdateAvailable()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("المستجدات والتحديثات") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } })
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("التطبيق", style = MaterialTheme.typography.titleSmall)
                        Text("الإصدار الحالي ${BuildConfig.VERSION_NAME}" + (manifest?.let { " — على الخادم ${it.appVersionName}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        if (appUpdate && !BuildConfig.PLAY_STORE) {
                            manifest?.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                            Button(onClick = { (manifest?.apkPage ?: manifest?.apkUrl)?.let { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } } }, modifier = Modifier.padding(top = 8.dp)) { Text("تنزيل الإصدار الجديد") }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("فهرس المكتبة", style = MaterialTheme.typography.titleSmall)
                        Text("المثبَّت: ${settings.dataVersion.ifBlank { "—" }}" + (manifest?.let { " — على الخادم: ${it.dataVersion}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        val st = libState
                        if (st is LibraryState.Downloading) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp)); Text(st.message, style = MaterialTheme.typography.labelSmall) }
                        Row(Modifier.padding(top = 8.dp)) {
                            if (dataUpdate) { Button(onClick = { scope.launch { app.library.installMaster() } }) { Text("تحديث الفهرس") }; Spacer(Modifier.width(8.dp)) }
                            OutlinedButton(onClick = { scope.launch { checking = true; runCatching { app.library.fetchManifest() }; checking = false } }, enabled = !checking) { Text(if (checking) "جارٍ الفحص…" else "فحص الآن") }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable { nav.go("newbooks") }) {
                    Column(Modifier.padding(14.dp)) {
                        Text("البحث عن كل الكتب الجديدة في الموقع", style = MaterialTheme.typography.titleSmall)
                        Text("فحص أقسام shamela.ws كلها ومقارنتها بالمكتبة، لا آخر الكتب فقط", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SectionHeader("آخر الكتب المضافة في shamela.ws")
                Text("الكتب التي وصلت إلى خادم NAS تُنزَّل من الخادم؛ وما لم يصل بعد يُجلب من الموقع صفحةً صفحة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            }
            val list = recent
            if (list == null) item { if (recentError != null) EmptyState("تعذر جلب المستجدات: $recentError", Modifier.height(160.dp)) else Loading(Modifier.height(160.dp)) }
            else items(list, key = { it.id }) { r ->
                val isInstalled = r.id in installed
                val job = jobs[r.id]
                Column(Modifier.fillMaxWidth().clickable { nav.go("book/${r.id}") }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(r.name, style = MaterialTheme.typography.titleSmall)
                    Text(r.authorName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(r.card, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        when {
                            isInstalled -> Text("مثبَّت", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                            job != null && job.status != org.murabbie.shamela.data.JobStatus.ERROR -> Text("جارٍ التنزيل… " + job.message, style = MaterialTheme.typography.labelMedium)
                            else -> TextButton(onClick = { scope.launch { val b = app.library.book(r.id) ?: Book(r.id, r.name, 0, 1, 0, listOfNotNull(r.authorId), r.authorId ?: 0, r.authorName, 0, null, null, org.json.JSONObject().put("card", r.card).toString(), null); app.downloads.enqueue(b) } }) {
                                Text(if (r.id in inIndex) "تنزيل من الخادم" else "جلب من الموقع")
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(app: App, nav: NavHostController) {
    var stats by remember { mutableStateOf<Map<String, Long>?>(null) }
    val installedVer by app.library.installedVersion.collectAsState()
    LaunchedEffect(Unit) { stats = app.library.stats() }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("عن التطبيق") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            Text("المكتبة الشاملة — نسخة الأندرويد", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            Text("تطبيق مستقل يقرأ بيانات المكتبة الشاملة (shamela.ws) من خادم NAS الخاص: الفهرس الكامل على الجهاز، والكتب تُنزَّل عند الحاجة (كتابًا كتابًا أو قسمًا كاملًا أو المكتبة كلها) وتُقرأ ويُبحث فيها دون اتصال. المستجدات تُجلب من موقع الشاملة مباشرة.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
            SectionHeader("الإحصائيات")
            val s = stats
            if (s != null) {
                KeyValue("الكتب", ArabicText.arabicDigits(s["books"] ?: 0))
                KeyValue("المؤلفون", ArabicText.arabicDigits(s["authors"] ?: 0))
                KeyValue("الأقسام", ArabicText.arabicDigits(s["categories"] ?: 0))
                KeyValue("الصفحات", ArabicText.arabicDigits(s["pages"] ?: 0))
                KeyValue("حجم المكتبة", ArabicText.formatSize(s["size"] ?: 0))
                KeyValue("المثبَّت", "${ArabicText.arabicDigits(remember(installedVer) { app.library.installedIds().size })} كتاب — ${ArabicText.formatSize(app.library.installedSize())}")
            }
            SectionHeader("الإصدار")
            KeyValue("التطبيق", "${BuildConfig.VERSION_NAME} (${ArabicText.arabicDigits(BuildConfig.VERSION_CODE)})")
            KeyValue("البيانات", app.settings.value.dataVersion.ifBlank { "—" })
            KeyValue("المصدر", "shamela.ws — نسخة قاعدة البيانات الرسمية ١٤٤٨")
            Spacer(Modifier.height(24.dp))
        }
    }
}
