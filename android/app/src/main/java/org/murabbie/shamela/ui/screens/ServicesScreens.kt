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
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import org.murabbie.shamela.App
import org.murabbie.shamela.data.ArabicText
import org.murabbie.shamela.data.Aya
import org.murabbie.shamela.data.Book
import org.murabbie.shamela.data.Page
import org.murabbie.shamela.data.Rawi
import org.murabbie.shamela.data.ServiceRef
import org.murabbie.shamela.data.ServicesState
import org.murabbie.shamela.data.Sura
import org.murabbie.shamela.ui.EmptyState
import org.murabbie.shamela.ui.Loading
import org.murabbie.shamela.ui.SearchField
import org.murabbie.shamela.ui.SectionHeader
import org.murabbie.shamela.ui.go

// =====================================================================
// مركز الخدمات: تثبيت قاعدة الخدمات ومداخل الخدمات
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(app: App, nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val state by app.services.state.collectAsState()
    val manifest by app.library.manifest.collectAsState()
    val diag by org.murabbie.shamela.data.NasHttp.status.collectAsState()
    var counts by remember { mutableStateOf<Map<String, Long>?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(state) { if (state is ServicesState.Ready) counts = runCatching { app.services.counts() }.getOrNull() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("خدمات الشاملة") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } })
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("قاعدة الخدمات", style = MaterialTheme.typography.titleSmall)
                        Text("الخدمات نفسها التي في نسخة ويندوز: تراجم الرواة، وتخريج الحديث في الكتب العشرة، وأسانيد الأحاديث ومرويات الراوي، والقرآن الكريم مع ربط الآية بكتب التفسير، والبحث بالجذر (الاشتقاق). تُنزَّل مرة واحدة من الخادم في ملف واحد.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(10.dp))
                        when (val s = state) {
                            is ServicesState.NotInstalled, is ServicesState.Error -> {
                                val size = manifest?.services?.size?.takeIf { it > 0 }?.let { ArabicText.formatSize(it) } ?: "نحو ٧٠ م.ب"
                                val raw = manifest?.services?.rawSize?.takeIf { it > 0 }?.let { ArabicText.formatSize(it) } ?: "نحو ٢٢٥ م.ب"
                                Text("حجم التنزيل $size، وتشغل على الجهاز $raw.", style = MaterialTheme.typography.bodySmall)
                                if (s is ServicesState.Error) { Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)); if (diag.isNotBlank()) Text(diag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Button(onClick = { scope.launch { app.services.install() } }, modifier = Modifier.padding(top = 8.dp)) { Text("تنزيل قاعدة الخدمات") }
                            }
                            is ServicesState.Downloading -> {
                                Text(s.message, style = MaterialTheme.typography.bodySmall)
                                if (s.total > 0) { LinearProgressIndicator(progress = { (s.bytes.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)); Text("${ArabicText.formatSize(s.bytes)} / ${ArabicText.formatSize(s.total)}", style = MaterialTheme.typography.labelSmall) }
                                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                            }
                            is ServicesState.Ready -> {
                                val c = counts
                                Text(if (c != null) "مثبَّتة: ${ArabicText.arabicDigits(c["rawi"] ?: 0)} راوٍ، ${ArabicText.arabicDigits(c["hadith"] ?: 0)} حديث مخرَّج، ${ArabicText.arabicDigits(c["esnad"] ?: 0)} إسناد، ${ArabicText.arabicDigits(c["aya"] ?: 0)} آية، ${ArabicText.arabicDigits(c["roots"] ?: 0)} كلمة بجذرها" else "مثبَّتة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                                TextButton(onClick = { confirmRemove = true }) { Text("حذف قاعدة الخدمات") }
                            }
                        }
                    }
                }
                TashkeelCard(app)
                val ready = state is ServicesState.Ready
                ServiceItem(Icons.Default.MenuBook, "القرآن الكريم وتفسير الآية", "تصفح السور والآيات، والبحث في الآيات، وفتح تفسير أي آية في ١٤ تفسيرًا", ready) { nav.go("quran") }
                ServiceItem(Icons.Default.Groups, "تراجم الرواة", "البحث عن الراوي وعرض ترجمته وأقوال النقاد فيه ومروياته في الكتب العشرة", ready) { nav.go("rowat") }
                ServiceItem(Icons.Default.Link, "تخريج الحديث والأسانيد", "من داخل أي حديث في الكتب العشرة (زر الخدمات في القارئ): مواضعه في الكتب الأخرى، ورواة إسناده، والنقر على اسم الراوي في الإسناد يفتح ترجمته", ready) { nav.go("takhrij") }
                ServiceItem(Icons.Default.Spellcheck, "البحث بالجذر (الاشتقاق)", "في شاشة البحث: خيار «بالجذر» يبحث عن كل مشتقات كلمات الاستعلام", ready) { nav.go("search") }
            }
        }
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("حذف قاعدة الخدمات") }, text = { Text("تُحذف قاعدة الخدمات من الجهاز ويمكن تنزيلها مرة أخرى.") },
        confirmButton = { TextButton(onClick = { scope.launch { app.services.uninstall() }; confirmRemove = false }) { Text("حذف") } }, dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("إلغاء") } })
}

/** بطاقة معجم التشكيل الآلي */
@Composable
private fun TashkeelCard(app: App) {
    val scope = rememberCoroutineScope()
    val st by app.tashkeel.state.collectAsState()
    val manifest by app.library.manifest.collectAsState()
    val settings by app.settings.state.collectAsState()
    var counts by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(st) { if (st is ServicesState.Ready) counts = runCatching { app.tashkeel.counts() }.getOrNull() }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("التشكيل الآلي لكل الكتب", style = MaterialTheme.typography.titleSmall)
            Text("معجم مبني من الكلمات المشكولة في نصوص المكتبة نفسها: يُشكِّل الكلمات غير المشكولة في أي كتاب بأرجح صورها مع مراعاة الكلمة السابقة، ويترك المشكول كما هو. يُفعَّل من خيارات الخط في القارئ (تشكيل آلي)، ويُحسِّن القراءة الصوتية.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            when (val s = st) {
                is ServicesState.NotInstalled, is ServicesState.Error -> {
                    val size = manifest?.tashkeel?.size?.takeIf { it > 0 }?.let { ArabicText.formatSize(it) } ?: "—"
                    Text("حجم التنزيل $size.", style = MaterialTheme.typography.bodySmall)
                    if (s is ServicesState.Error) Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { scope.launch { if (app.tashkeel.install()) app.settings.update { it.copy(autoTashkeel = true, showTashkeel = true) } } }, modifier = Modifier.padding(top = 6.dp)) { Text("تنزيل معجم التشكيل") }
                }
                is ServicesState.Downloading -> {
                    Text(s.message, style = MaterialTheme.typography.bodySmall)
                    if (s.total > 0) LinearProgressIndicator(progress = { (s.bytes.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                is ServicesState.Ready -> {
                    Text(counts?.let { "مثبَّت: ${ArabicText.arabicDigits(it.first)} كلمة، ${ArabicText.arabicDigits(it.second)} سياقًا ثنائيًّا" } ?: "مثبَّت", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("التشكيل الآلي في القارئ", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.material3.Switch(checked = settings.autoTashkeel, onCheckedChange = { c -> app.settings.update { it.copy(autoTashkeel = c, showTashkeel = if (c) true else it.showTashkeel) } })
                    }
                    TextButton(onClick = { confirmRemove = true }) { Text("حذف المعجم") }
                }
            }
        }
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("حذف معجم التشكيل") }, text = { Text("يمكن تنزيله مرة أخرى.") },
        confirmButton = { TextButton(onClick = { scope.launch { app.tashkeel.uninstall() }; confirmRemove = false }) { Text("حذف") } }, dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("إلغاء") } })
}

@Composable
private fun ServiceItem(icon: ImageVector, title: String, sub: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(14.dp))
        Column { Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline); Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

/** شرح خدمة التخريج والكتب المشمولة */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TakhrijInfoScreen(app: App, nav: NavHostController) {
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    LaunchedEffect(Unit) { books = runCatching { app.services.hadithBooks() }.getOrDefault(emptyList()) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("تخريج الحديث والأسانيد") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } })
        LazyColumn {
            item { Text("افتح أي حديث في أحد هذه الكتب ثم اضغط زر «الخدمات» في شريط القارئ: تظهر مواضع الحديث نفسه في الكتب الأخرى، ورواة إسناده مع تراجمهم. وأسماء الرواة في نص الإسناد مسطَّرة؛ النقر على أحدها يفتح ترجمته.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
            item { SectionHeader("الكتب المشمولة") }
            items(books, key = { it.id }) { b ->
                Column(Modifier.fillMaxWidth().clickable { nav.go("reader/${b.id}") }.padding(horizontal = 16.dp, vertical = 10.dp)) { Text(b.name, style = MaterialTheme.typography.titleSmall); Text(b.authorName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

// =====================================================================
// القرآن الكريم والتفسير
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuranScreen(app: App, nav: NavHostController) {
    var suras by remember { mutableStateOf<List<Sura>>(emptyList()) }
    var sura by remember { mutableStateOf<Sura?>(null) }
    var ayat by remember { mutableStateOf<List<Aya>>(emptyList()) }
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Aya>?>(null) }
    var tafsirAya by remember { mutableStateOf<Aya?>(null) }
    var uthmani by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { suras = runCatching { app.services.suras() }.getOrDefault(emptyList()) }
    LaunchedEffect(sura) { ayat = sura?.let { runCatching { app.services.ayat(it.id) }.getOrDefault(emptyList()) } ?: emptyList() }
    LaunchedEffect(q) { results = if (q.isBlank()) null else runCatching { app.services.searchAyat(q) }.getOrDefault(emptyList()) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(sura?.let { "سورة ${it.name}" } ?: "القرآن الكريم") },
            navigationIcon = { IconButton(onClick = { if (sura != null) sura = null else nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } },
            actions = { TextButton(onClick = { uthmani = !uthmani }) { Text(if (uthmani) "رسم عثماني" else "رسم إملائي") } },
        )
        SearchField(q, { q = it }, "بحث في آيات القرآن")
        val res = results
        if (res != null) {
            if (res.isEmpty()) EmptyState("لا نتائج")
            else LazyColumn { items(res, key = { it.id }) { a -> AyaRow(a, suras, uthmani) { tafsirAya = a } } }
        } else if (sura == null) {
            LazyColumn {
                items(suras, key = { it.id }) { s ->
                    Row(Modifier.fillMaxWidth().clickable { sura = s }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ArabicText.arabicDigits(s.id), Modifier.width(36.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("سورة ${s.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text("${ArabicText.arabicDigits(s.ayat)} آية", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        } else {
            if (ayat.isEmpty()) Loading() else LazyColumn { items(ayat, key = { it.id }) { a -> AyaRow(a, suras, uthmani) { tafsirAya = a } } }
        }
    }
    tafsirAya?.let { a -> TafsirSheet(app, nav, a, suras) { tafsirAya = null } }
}

@Composable
private fun AyaRow(a: Aya, suras: List<Sura>, uthmani: Boolean, onClick: () -> Unit) {
    val sname = suras.firstOrNull { it.id == a.sura }?.name ?: ""
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text((if (uthmani && a.uthmani.isNotBlank()) a.uthmani else a.text) + " ﴿${ArabicText.arabicDigits(a.aya)}﴾", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 36.sp), textAlign = TextAlign.Justify)
        Text("[$sname: ${ArabicText.arabicDigits(a.aya)}] — اضغط لعرض التفسير", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

/** ورقة تفسير آية: مواضعها في كتب التفسير */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TafsirSheet(app: App, nav: NavHostController, a: Aya, suras: List<Sura>, onDismiss: () -> Unit) {
    var refs by remember { mutableStateOf<List<ServiceRef>?>(null) }
    LaunchedEffect(a.id) { refs = runCatching { app.services.tafsirFor(a.id) }.getOrDefault(emptyList()) }
    val sname = suras.firstOrNull { it.id == a.sura }?.name ?: ""
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("تفسير الآية", style = MaterialTheme.typography.titleMedium)
            Text("﴿${a.text}﴾ [$sname: ${ArabicText.arabicDigits(a.aya)}]", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            val list = refs
            if (list == null) Loading(Modifier.height(120.dp))
            else if (list.isEmpty()) EmptyState("لا مواضع لهذه الآية في كتب التفسير المشمولة", Modifier.height(120.dp))
            else LazyColumn(Modifier.height(420.dp)) {
                items(list) { r ->
                    Text(r.bookName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth().clickable { onDismiss(); nav.go("reader/${r.bookId}/${r.pageId}") }.padding(vertical = 12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// =====================================================================
// الرواة
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowatSearchScreen(app: App, nav: NavHostController) {
    var q by rememberSaveable { mutableStateOf("") }
    var list by remember { mutableStateOf<List<Rawi>>(emptyList()) }
    LaunchedEffect(q) { list = if (q.isBlank()) emptyList() else runCatching { app.services.searchRawi(q) }.getOrDefault(emptyList()) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("تراجم الرواة") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } })
        SearchField(q, { q = it }, "اسم الراوي (مثل: الحميدي، شعبة، ابن جريج)")
        if (q.isBlank()) EmptyState("اكتب اسم الراوي أو جزءًا منه. القاعدة تحوي نحو ١٩ ألف راوٍ من رواة الكتب العشرة.")
        else if (list.isEmpty()) EmptyState("لا نتائج")
        else LazyColumn { items(list, key = { it.id }) { r ->
            Column(Modifier.fillMaxWidth().clickable { nav.go("rawi/${r.id}") }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(r.name, style = MaterialTheme.typography.titleSmall)
                Row { if (r.deathLabel.isNotEmpty()) Text(r.deathLabel + "  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); r.details.firstOrNull { it.first.contains("الرتبة عند ابن حجر") }?.let { Text(it.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawiScreen(app: App, nav: NavHostController, id: Int) {
    var rawi by remember { mutableStateOf<Rawi?>(null) }
    var notFound by remember { mutableStateOf(false) }
    var pages by remember { mutableStateOf<List<ServiceRef>?>(null) }
    var tab by remember { mutableStateOf(0) }
    LaunchedEffect(id) { val r = runCatching { app.services.rawi(id) }.getOrNull(); rawi = r; notFound = r == null }
    LaunchedEffect(id, tab) { if (tab == 2 && pages == null) pages = runCatching { app.services.rawiPages(id) }.getOrDefault(emptyList()) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(rawi?.name ?: "ترجمة الراوي", maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } })
        val r = rawi
        if (r == null) { if (notFound) EmptyState("لا ترجمة لهذا الراوي في قاعدة الخدمات") else Loading(); return }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("الترجمة") }); Spacer(Modifier.width(6.dp))
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("أقوال النقاد (${ArabicText.arabicDigits(r.jarh.size)})") }); Spacer(Modifier.width(6.dp))
            FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("مروياته") })
        }
        LazyColumn(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text(r.full.ifBlank { r.name }, style = MaterialTheme.typography.titleMedium)
                            if (r.deathLabel.isNotEmpty()) Text(r.deathLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (r.details.isEmpty()) item { EmptyState("لا تفاصيل", Modifier.height(100.dp)) }
                    items(r.details) { (label, value) ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
                1 -> {
                    if (r.jarh.isEmpty()) item { EmptyState("لا أقوال مسجَّلة", Modifier.height(100.dp)) }
                    r.jarh.forEach { j ->
                        item { SectionHeader(j.critic) }
                        items(j.items) { (text, source) ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text(text, style = MaterialTheme.typography.bodyMedium)
                                Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                else -> {
                    val p = pages
                    if (p == null) item { Loading(Modifier.height(120.dp)) }
                    else if (p.isEmpty()) item { EmptyState("لا مرويات له في الكتب العشرة", Modifier.height(120.dp)) }
                    else {
                        item { Text("${ArabicText.arabicDigits(p.size)} موضعًا في الكتب العشرة يقع فيها الراوي في الإسناد", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                        p.groupBy { it.bookName }.forEach { (book, refs) ->
                            item { SectionHeader("$book (${ArabicText.arabicDigits(refs.size)})") }
                            items(refs) { ref ->
                                Text("صفحة ${ArabicText.arabicDigits(ref.pageId)}" + (if (ref.extra > 0) " — حديث رقم ${ArabicText.arabicDigits(ref.extra)}" else ""), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().clickable { nav.go("reader/${ref.bookId}/${ref.pageId}") }.padding(horizontal = 16.dp, vertical = 10.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** ورقة موجزة لترجمة راوٍ (من النقر على اسمه في الإسناد) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RawiSheet(app: App, nav: NavHostController, id: Int, onDismiss: () -> Unit) {
    var rawi by remember { mutableStateOf<Rawi?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val ready by app.services.state.collectAsState()
    LaunchedEffect(id) { rawi = runCatching { app.services.rawi(id) }.getOrNull(); loaded = true }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (ready !is ServicesState.Ready) {
                Text("ترجمة الراوي", style = MaterialTheme.typography.titleMedium)
                Text("تحتاج تراجم الرواة إلى قاعدة الخدمات (تنزيل مرة واحدة).", modifier = Modifier.padding(vertical = 8.dp))
                Button(onClick = { onDismiss(); nav.go("services") }) { Text("تنزيل قاعدة الخدمات") }
            } else {
                val r = rawi
                if (!loaded) Loading(Modifier.height(120.dp))
                else if (r == null) EmptyState("لا ترجمة لهذا الراوي", Modifier.height(120.dp))
                else {
                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                    if (r.full.isNotBlank() && r.full != r.name) Text(r.full, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    if (r.deathLabel.isNotEmpty()) Text(r.deathLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    r.details.filter { it.first.contains("الرتبة") || it.first.contains("طبقة") || it.first.contains("الكنية") || it.first.contains("النسب") }.take(5).forEach { (l, v) ->
                        Row(Modifier.padding(vertical = 2.dp)) { Text("$l: ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall); Text(v, style = MaterialTheme.typography.bodySmall) }
                    }
                    r.jarh.firstOrNull()?.let { j -> j.items.firstOrNull()?.let { Text("${j.critic}: ${it.first}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp), maxLines = 3, overflow = TextOverflow.Ellipsis) } }
                    Row(Modifier.padding(top = 12.dp)) {
                        Button(onClick = { onDismiss(); nav.go("rawi/${r.id}") }) { Text("الترجمة كاملة ومروياته") }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

// =====================================================================
// خدمات الصفحة في القارئ: تخريج الحديث، رواة الإسناد، تفسير الآيات
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageServicesSheet(app: App, nav: NavHostController, book: Book, page: Page, onDismiss: () -> Unit, onRawi: (Int) -> Unit) {
    val ready by app.services.state.collectAsState()
    var occ by remember { mutableStateOf<List<ServiceRef>?>(null) }
    var chains by remember { mutableStateOf<List<List<Int>>>(emptyList()) }
    var names by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var ayat by remember { mutableStateOf<List<Aya>>(emptyList()) }
    var suras by remember { mutableStateOf<List<Sura>>(emptyList()) }
    var tafsirAya by remember { mutableStateOf<Aya?>(null) }
    val hadithKeys = remember(page.id) { app.services.hadithKeys(page.services) }
    val ayaKeys = remember(page.id) { app.services.ayaKeys(page.services) }
    LaunchedEffect(page.id, ready) {
        if (ready !is ServicesState.Ready) return@LaunchedEffect
        occ = runCatching { app.services.hadithOccurrences(hadithKeys, book.id, page.id) }.getOrDefault(emptyList())
        val c = app.services.esnadIds(page.services).ifEmpty { runCatching { app.services.esnadFor(book.id, page.id) }.getOrDefault(emptyList()) }
        chains = c
        names = runCatching { app.services.rawiNames(c.flatten().toSet()) }.getOrDefault(emptyMap())
        ayat = runCatching { app.services.ayatByIds(ayaKeys) }.getOrDefault(emptyList())
        suras = runCatching { app.services.suras() }.getOrDefault(emptyList())
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("خدمات الصفحة", style = MaterialTheme.typography.titleMedium)
            Text("${book.shortName} — ${page.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ready !is ServicesState.Ready) {
                Text("تحتاج الخدمات (تخريج الحديث، تراجم الرواة، تفسير الآية) إلى قاعدة الخدمات — تُنزَّل مرة واحدة.", modifier = Modifier.padding(vertical = 10.dp))
                Button(onClick = { onDismiss(); nav.go("services") }) { Text("تنزيل قاعدة الخدمات") }
                Spacer(Modifier.height(28.dp)); return@Column
            }
            LazyColumn(Modifier.height(480.dp)) {
                // التخريج
                item { SectionHeader("تخريج الحديث") }
                val o = occ
                if (hadithKeys.isEmpty()) item { Text("لا أرقام أحاديث مربوطة بهذه الصفحة (الخدمة تعمل في الكتب العشرة).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp)) }
                else if (o == null) item { Loading(Modifier.height(60.dp)) }
                else if (o.isEmpty()) item { Text("لا مواضع أخرى لهذا الحديث في الكتب المشمولة.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp)) }
                else {
                    item { Text("الحديث رقم ${hadithKeys.joinToString("، ") { ArabicText.arabicDigits(it) }} — ${ArabicText.arabicDigits(o.size)} موضعًا في كتب أخرى:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(o) { r ->
                        Row(Modifier.fillMaxWidth().clickable { onDismiss(); nav.go("reader/${r.bookId}/${r.pageId}") }.padding(vertical = 10.dp)) {
                            Text(r.bookName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("صفحة ${ArabicText.arabicDigits(r.pageId)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
                // الرواة
                item { SectionHeader("رواة الإسناد") }
                if (chains.isEmpty()) item { Text("لا إسناد مربوط بهذه الصفحة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp)) }
                chains.forEachIndexed { ci, chain ->
                    if (chains.size > 1) item { Text("الإسناد ${ArabicText.arabicDigits(ci + 1)}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp)) }
                    items(chain) { rid ->
                        Text(names[rid] ?: "راوٍ رقم ${ArabicText.arabicDigits(rid)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().clickable { onRawi(rid) }.padding(vertical = 8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
                // الآيات
                if (ayat.isNotEmpty()) {
                    item { SectionHeader("آيات الصفحة وتفسيرها") }
                    items(ayat, key = { it.id }) { a ->
                        val sname = suras.firstOrNull { it.id == a.sura }?.name ?: ""
                        Column(Modifier.fillMaxWidth().clickable { tafsirAya = a }.padding(vertical = 8.dp)) {
                            Text("﴿${a.text}﴾", style = MaterialTheme.typography.bodyMedium)
                            Text("[$sname: ${ArabicText.arabicDigits(a.aya)}] — التفسير في كتب أخرى", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
                item {
                    Row(Modifier.padding(vertical = 12.dp)) {
                        OutlinedButton(onClick = { onDismiss(); nav.go("quran") }) { Text("القرآن والتفسير") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { onDismiss(); nav.go("rowat") }) { Text("بحث عن راوٍ") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    tafsirAya?.let { a -> TafsirSheet(app, nav, a, suras) { tafsirAya = null } }
}
