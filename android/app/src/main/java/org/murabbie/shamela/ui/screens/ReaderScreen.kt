package org.murabbie.shamela.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.runtime.DisposableEffect
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.murabbie.shamela.App
import org.murabbie.shamela.data.ArabicText
import org.murabbie.shamela.data.Book
import org.murabbie.shamela.data.BookReader
import org.murabbie.shamela.data.BookStore
import org.murabbie.shamela.data.Note
import org.murabbie.shamela.data.Page
import org.murabbie.shamela.data.Title
import org.murabbie.shamela.ui.Loading
import org.murabbie.shamela.ui.PageColors
import org.murabbie.shamela.ui.PageRenderer
import org.murabbie.shamela.ui.go

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(app: App, nav: NavHostController, bookId: Int, startPage: Int?, query: String?) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val settings by app.settings.state.collectAsState()
    var book by remember { mutableStateOf<Book?>(null) }
    var reader by remember { mutableStateOf<BookReader?>(null) }
    var ids by remember { mutableStateOf<List<Triple<Int, String?, Int?>>>(emptyList()) }
    var startIndex by remember { mutableStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    val pages = remember { mutableStateMapOf<Int, Page>() }
    val voc = remember { mutableStateMapOf<Int, String>() }   // نصوص مشكولة آليًّا
    var listening by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    val speakState by app.speaker.state.collectAsState()
    val tkState by app.tashkeel.state.collectAsState()
    var showToc by remember { mutableStateOf(false) }
    var showGoto by remember { mutableStateOf(false) }
    var showFont by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var showServices by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var rawiId by remember { mutableStateOf<Int?>(null) }
    var menu by remember { mutableStateOf(false) }
    var bookmarked by remember { mutableStateOf(false) }
    var sectionTitle by remember { mutableStateOf("") }
    var titles by remember { mutableStateOf<List<Title>>(emptyList()) }
    val highlightWords = remember(query) { if (query.isNullOrBlank()) emptyList() else ArabicText.queryWords(query) }

    var fetching by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    val dljob by app.downloads.jobs.collectAsState()
    val diag by org.murabbie.shamela.data.NasHttp.status.collectAsState()

    LaunchedEffect(bookId, attempt) {
        try {
            error = null
            val b = app.book(bookId) ?: throw IllegalStateException("الكتاب غير موجود في الفهرس")
            book = b
            // فتح فوري: إن لم يكن الكتاب مثبَّتًا يُجلب الآن عند الطلب ثم يُقرأ
            if (!app.library.isInstalled(bookId)) {
                fetching = true
                val ok = app.downloads.ensureInstalled(b)
                fetching = false
                if (!ok) throw IllegalStateException("تعذّر جلب الكتاب. تحقّق من الاتصال ثم أعد المحاولة.")
            }
            val r = BookReader(bookId, app.library.bookFile(bookId))
            val index = r.pageIndex()
            if (index.isEmpty()) throw IllegalStateException("الكتاب فارغ أو تعذّر فتحه")
            val target = startPage ?: app.userDb.position(bookId) ?: index.firstOrNull()?.first ?: 1
            val idx = index.indexOfFirst { it.first == target }.let { if (it < 0) index.indexOfFirst { p -> p.first >= target }.coerceAtLeast(0) else it }
            ids = index; reader = r; startIndex = idx
        } catch (e: Exception) { fetching = false; error = e.message }
    }

    if (error != null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("تعذر فتح الكتاب", style = MaterialTheme.typography.titleMedium)
            Text(error ?: "", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            if (diag.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(diag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth()) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { attempt++ }) { Text("إعادة المحاولة") }
                Button(onClick = { nav.popBackStack() }) { Text("رجوع") }
            }
        }
        return
    }
    if (fetching) {
        val j = dljob[bookId]
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(book?.name ?: "", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            val p = if (j != null && j.total > 0) (j.bytes.toFloat() / j.total).coerceIn(0f, 1f) else null
            if (p != null) androidx.compose.material3.LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            else androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Text(if (j != null && j.total > 0) "جلب الكتاب للقراءة… ${ArabicText.formatSize(j.bytes)} / ${ArabicText.formatSize(j.total)}" else "جلب الكتاب للقراءة…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (diag.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(diag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth()) }
        }
        return
    }
    val b = book; val r = reader
    if (b == null || r == null || startIndex < 0 || ids.isEmpty()) { Loading(); return }

    val pager = rememberPagerState(initialPage = startIndex) { ids.size }
    val currentId = ids[pager.currentPage].first
    val currentPage = pages[currentId]

    // تحميل الصفحة الحالية والمجاورة
    LaunchedEffect(pager.currentPage) {
        for (d in listOf(0, 1, -1, 2)) {
            val i = pager.currentPage + d
            if (i !in ids.indices) continue
            val id = ids[i].first
            if (pages[id] == null) r.page(id)?.let { pages[id] = it }
        }
        // حفظ الموضع والعنوان الجاري
        val p = pages[currentId] ?: r.page(currentId)
        if (p != null) { app.userDb.setPosition(b, p); bookmarked = app.userDb.isBookmarked(bookId, currentId) }
        sectionTitle = r.titleForPage(currentId)?.text ?: ""
        if (settings.autoTashkeel && app.tashkeel.installed) for (d in listOf(0, 1, -1)) {
            val id = ids.getOrNull(pager.currentPage + d)?.first ?: continue
            val pg = pages[id] ?: continue
            if (voc[id] == null) voc[id] = app.tashkeel.vocalizeHtml(pg.body)
        }
        if (pages.size > 60) { val keep = (pager.currentPage - 5..pager.currentPage + 5).mapNotNull { ids.getOrNull(it)?.first }.toSet(); pages.keys.filter { it !in keep }.forEach { pages.remove(it) } }
    }

    // عند تغيير خيار التشكيل الآلي
    LaunchedEffect(settings.autoTashkeel, tkState) {
        voc.clear()
        if (settings.autoTashkeel && app.tashkeel.installed) { val id = currentId; pages[id]?.let { voc[id] = app.tashkeel.vocalizeHtml(it.body) } }
    }
    // القراءة الصوتية: عند انتهاء الصفحة ينتقل إلى التالية ويكمل
    fun speakPage(id: Int) {
        scope.launch {
            val pg = pages[id] ?: r.page(id) ?: return@launch
            val body = if (app.tashkeel.installed) app.tashkeel.vocalizeHtml(pg.body) else pg.body
            val plain = PageRenderer.render(body, PageColors(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Black), 16.sp, true).plain
            app.speaker.rate = settings.ttsRate
            app.speaker.pitch = settings.ttsPitch
            app.speaker.voiceName = settings.ttsVoice
            app.speaker.cloud = if (settings.useCloudTts && settings.cloudTtsKey.isNotBlank()) org.murabbie.shamela.data.CloudTts(settings.cloudTtsKey.trim(), settings.cloudTtsRegion.trim(), settings.cloudTtsVoice) else null
            app.speaker.speak(plain)
        }
    }
    LaunchedEffect(listening) {
        app.speaker.onPageDone = {
            if (listening) scope.launch {
                if (pager.currentPage < ids.size - 1) { pager.scrollToPage(pager.currentPage + 1); speakPage(ids[pager.currentPage].first) }
                else { listening = false; app.speaker.stop() }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { app.speaker.stop(); app.speaker.onPageDone = null } }

    val colors = PageColors(
        heading = MaterialTheme.colorScheme.primary, quote = MaterialTheme.colorScheme.tertiary, ref = MaterialTheme.colorScheme.secondary.let { if (settings.themeMode == 2) it else MaterialTheme.colorScheme.onSecondaryContainer },
        highlight = MaterialTheme.colorScheme.secondaryContainer, muted = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val fontFamily = when (settings.readerFont) { 1 -> FontFamily.Serif; 2 -> FontFamily.SansSerif; else -> FontFamily.Default }
    val bodyStyle = TextStyle(fontSize = settings.readerFontSize.sp, lineHeight = (settings.readerFontSize * settings.readerLineHeight).sp, fontFamily = fontFamily, textAlign = TextAlign.Justify)
    val footStyle = TextStyle(fontSize = (settings.readerFontSize * 0.78f).sp, lineHeight = (settings.readerFontSize * 0.78f * settings.readerLineHeight).sp, fontFamily = fontFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)

    fun pageText(p: Page): String {
        val plain = PageRenderer.render(p.body, colors, bodyStyle.fontSize, settings.showTashkeel).plain
        val foot = p.foot?.let { ArabicText.stripTags(it.replace('\r', '\n')) }?.takeIf { it.isNotBlank() }
        return buildString { append(plain); if (foot != null) append("\n__________\n").append(foot); append("\n\n[").append(b.name).append("، ").append(p.label).append("]") }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(b.shortName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(listOf(currentPage?.label ?: "", sectionTitle).filter { it.isNotBlank() }.joinToString(" — "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") } },
            actions = {
                IconButton(onClick = { scope.launch { titles = r.titles(); showToc = true } }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "الفهرس") }
                IconButton(onClick = { scope.launch { val p = currentPage ?: return@launch; if (bookmarked) app.userDb.removeBookmarkAt(bookId, currentId) else app.userDb.addBookmark(b, p); bookmarked = !bookmarked } }) {
                    Icon(if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = "علامة", tint = if (bookmarked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = { nav.go("search/$bookId") }) { Icon(Icons.Default.Search, contentDescription = "بحث في الكتاب") }
                IconButton(onClick = { if (listening) { listening = false; app.speaker.stop() } else { listening = true; speakPage(currentId) } }) { Icon(Icons.Default.VolumeUp, contentDescription = "قراءة الكتاب صوتيًّا", tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = { showServices = true }) { Icon(Icons.Default.Hub, contentDescription = "الخدمات: التخريج والرواة والتفسير") }
                IconButton(onClick = { showFont = true }) { Icon(Icons.Default.TextFields, contentDescription = "الخط") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "المزيد") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("الانتقال إلى صفحة") }, onClick = { menu = false; showGoto = true })
                        DropdownMenuItem(text = { Text("نسخ الصفحة مع المرجع") }, onClick = { menu = false; currentPage?.let { p -> (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("page", pageText(p))) } })
                        DropdownMenuItem(text = { Text("مشاركة الصفحة") }, onClick = { menu = false; currentPage?.let { p -> ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pageText(p)), "مشاركة")) } })
                        DropdownMenuItem(text = { Text("تعليقاتي على الصفحة") }, onClick = { menu = false; showNotes = true })
                        DropdownMenuItem(text = { Text("خدمات الصفحة (تخريج، رواة، تفسير)") }, onClick = { menu = false; showServices = true })
                        DropdownMenuItem(text = { Text("إخراج إلى Word / PDF…") }, onClick = { menu = false; scope.launch { if (titles.isEmpty()) titles = r.titles(); showExport = true } })
                        DropdownMenuItem(text = { Text("بطاقة الكتاب") }, onClick = { menu = false; nav.go("book/$bookId") })
                        DropdownMenuItem(text = { Text("فتح في shamela.ws") }, onClick = { menu = false; runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shamela.ws/book/$bookId/$currentId"))) } })
                    }
                }
            },
        )
        HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1, key = { ids[it].first }) { index ->
            val id = ids[index].first
            val p = pages[id]
            if (p == null) {
                LaunchedEffect(id) { r.page(id)?.let { pages[id] = it } }
                Loading()
            } else {
                val bodyHtml = voc[p.id] ?: p.body
                val rendered = remember(p.id, bodyHtml, settings.readerFontSize, settings.showTashkeel, highlightWords, colors) { PageRenderer.render(bodyHtml, colors, bodyStyle.fontSize, settings.showTashkeel, highlightWords, false) }
                val foot = remember(p.id, settings.showTashkeel, highlightWords) { p.foot?.takeIf { it.isNotBlank() }?.let { PageRenderer.renderFoot(it, colors, settings.showTashkeel, highlightWords, false) } }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
                    var layout by remember(p.id) { mutableStateOf<TextLayoutResult?>(null) }
                    SelectionContainer {
                        Column {
                            Text(rendered.text, style = bodyStyle, onTextLayout = { layout = it }, modifier = Modifier.pointerInput(rendered) {
                                detectTapGestures { pos ->
                                    val off = layout?.getOffsetForPosition(pos) ?: return@detectTapGestures
                                    rendered.text.getStringAnnotations(PageRenderer.RAWI_TAG, off, off).firstOrNull()?.let { a -> a.item.toIntOrNull()?.let { rawiId = it } }
                                }
                            })
                            if (foot != null && settings.showFootnotes) {
                                HorizontalDivider(Modifier.padding(vertical = 10.dp).width(120.dp), color = MaterialTheme.colorScheme.outline)
                                Text(foot, style = footStyle)
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(p.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        if (listening) Surface(tonalElevation = 4.dp, color = MaterialTheme.colorScheme.secondaryContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (speakState.playing) app.speaker.pause() else app.speaker.resume() }) { Icon(if (speakState.playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "تشغيل/إيقاف مؤقت") }
                    IconButton(onClick = { listening = false; app.speaker.stop() }) { Icon(Icons.Default.Stop, contentDescription = "إيقاف") }
                    IconButton(onClick = { app.speaker.pitch = settings.ttsPitch; app.speaker.warmup(); showVoice = true }) { Icon(Icons.Default.RecordVoiceOver, contentDescription = "اختيار الصوت") }
                    Column(Modifier.weight(1f)) {
                        Text(if (!speakState.available) speakState.message else "قراءة الكتاب صوتيًّا — " + (currentPage?.label ?: "") + (if (speakState.chunks > 1) "  (${ArabicText.arabicDigits(speakState.chunk)}/${ArabicText.arabicDigits(speakState.chunks)})" else ""), style = MaterialTheme.typography.labelMedium, maxLines = 3)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("السرعة", style = MaterialTheme.typography.labelSmall)
                            Slider(value = settings.ttsRate, onValueChange = { v -> app.settings.update { it.copy(ttsRate = v) }; app.speaker.rate = v }, valueRange = 0.5f..2.0f, modifier = Modifier.weight(1f).height(24.dp))
                            Text("×${String.format(java.util.Locale.US, "%.1f", settings.ttsRate)}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (!speakState.available) TextButton(onClick = { runCatching { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS")) } }) { Text("إعدادات محرك النطق") }
            }
        }
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope.launch { if (pager.currentPage > 0) pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) { Icon(Icons.Default.ChevronRight, contentDescription = "السابقة") }
                Column(Modifier.weight(1f).clickable { showGoto = true }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text((currentPage?.label ?: "") + "  ·  " + ArabicText.arabicDigits(pager.currentPage + 1) + " / " + ArabicText.arabicDigits(ids.size), style = MaterialTheme.typography.labelMedium)
                    Slider(value = pager.currentPage.toFloat(), onValueChange = { v -> scope.launch { pager.scrollToPage(v.toInt().coerceIn(0, ids.size - 1)) } }, valueRange = 0f..(ids.size - 1).coerceAtLeast(1).toFloat(), modifier = Modifier.height(24.dp))
                }
                IconButton(onClick = { scope.launch { if (pager.currentPage < ids.size - 1) pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < ids.size - 1) { Icon(Icons.Default.ChevronLeft, contentDescription = "التالية") }
            }
        }
    }

    if (showToc) ModalBottomSheet(onDismissRequest = { showToc = false }) {
        Text("فهرس الموضوعات", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        val listState = rememberLazyListState()
        LaunchedEffect(titles) { val cur = titles.indexOfLast { it.page <= currentId }; if (cur > 0) listState.scrollToItem(cur) }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(520.dp)) {
            items(titles, key = { it.id }) { t ->
                val active = titles.indexOfLast { it.page <= currentId }.let { it >= 0 && titles[it].id == t.id }
                Text(t.text, style = if (t.level == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().clickable { showToc = false; scope.launch { val i = ids.indexOfFirst { it.first >= t.page }; if (i >= 0) pager.scrollToPage(i) } }.padding(start = (16 + t.level * 16).dp, end = 16.dp, top = 8.dp, bottom = 8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }

    if (showGoto) GotoDialog(r, ids, currentPage, onDismiss = { showGoto = false }) { id -> showGoto = false; scope.launch { val i = ids.indexOfFirst { it.first == id }; if (i >= 0) pager.scrollToPage(i) } }

    if (showFont) ModalBottomSheet(onDismissRequest = { showFont = false }) {
        Column(Modifier.padding(16.dp)) {
            Text("حجم الخط: ${ArabicText.arabicDigits(settings.readerFontSize)}", style = MaterialTheme.typography.titleSmall)
            Slider(value = settings.readerFontSize.toFloat(), onValueChange = { v -> app.settings.update { it.copy(readerFontSize = v.toInt()) } }, valueRange = 12f..40f, steps = 27)
            Text("تباعد الأسطر", style = MaterialTheme.typography.titleSmall)
            Slider(value = settings.readerLineHeight, onValueChange = { v -> app.settings.update { it.copy(readerLineHeight = v) } }, valueRange = 1.2f..2.6f)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("الخط:", Modifier.width(60.dp)); listOf("افتراضي", "مُسند (Serif)", "بسيط").forEachIndexed { i, n -> org.murabbie.shamela.ui.Chip(n, settings.readerFont == i) { app.settings.update { it.copy(readerFont = i) } } } }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { Text("إظهار التشكيل", Modifier.weight(1f)); Switch(checked = settings.showTashkeel, onCheckedChange = { c -> app.settings.update { it.copy(showTashkeel = c) } }) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("تشكيل آلي للنص غير المشكول"); Text(if (app.tashkeel.installed) "من معجم التشكيل (بأرجح صور الكلمات في المكتبة)" else "يحتاج تنزيل معجم التشكيل من «خدمات الشاملة»", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(checked = settings.autoTashkeel && app.tashkeel.installed, onCheckedChange = { c -> if (app.tashkeel.installed) app.settings.update { it.copy(autoTashkeel = c, showTashkeel = if (c) true else it.showTashkeel) } else { showFont = false; nav.go("services") } })
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("إظهار الهوامش", Modifier.weight(1f)); Switch(checked = settings.showFootnotes, onCheckedChange = { c -> app.settings.update { it.copy(showFootnotes = c) } }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("إبقاء الشاشة مضاءة", Modifier.weight(1f)); Switch(checked = settings.keepScreenOn, onCheckedChange = { c -> app.settings.update { it.copy(keepScreenOn = c) } }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("المظهر:", Modifier.width(60.dp)); listOf("النظام", "فاتح", "داكن", "ورقي").forEachIndexed { i, n -> org.murabbie.shamela.ui.Chip(n, settings.themeMode == i) { app.settings.update { it.copy(themeMode = i) } } } }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showNotes) NotesSheet(app, bookId, currentId, onDismiss = { showNotes = false })
    if (showServices) currentPage?.let { p -> PageServicesSheet(app, nav, b, p, onDismiss = { showServices = false }) { id -> showServices = false; rawiId = id } }
    rawiId?.let { id -> RawiSheet(app, nav, id, onDismiss = { rawiId = null }) }
    if (showVoice) VoiceSheet(app, onDismiss = { showVoice = false; app.speaker.endPreview(); if (listening) speakPage(currentId) })
    if (showExport) ExportDialog(app, b, r, ids, currentId, titles, onDismiss = { showExport = false })
}

@Composable
private fun GotoDialog(r: BookReader, ids: List<Triple<Int, String?, Int?>>, current: Page?, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var parts by remember { mutableStateOf<List<String>>(emptyList()) }
    var part by remember { mutableStateOf(current?.part ?: "") }
    var page by remember { mutableStateOf(current?.page?.toString() ?: "") }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { parts = r.parts() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("الانتقال إلى صفحة") },
        text = {
            Column {
                if (parts.isNotEmpty()) {
                    Text("الجزء", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { OutlinedTextField(value = part, onValueChange = { part = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text(parts.joinToString("، ") { ArabicText.arabicDigits(it) }, maxLines = 1) }) }
                }
                Text("رقم الصفحة", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = page, onValueChange = { page = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val pg = ArabicText.normalize(page).toIntOrNull()
                if (pg == null) { err = "أدخل رقم الصفحة"; return@TextButton }
                scope.launch {
                    val pid = r.pageIdFor(ArabicText.normalize(part).takeIf { it.isNotBlank() }, pg)
                    if (pid == null) err = "لا توجد هذه الصفحة" else onGo(pid)
                }
            }) { Text("انتقال") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesSheet(app: App, bookId: Int, pageId: Int, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(pageId) { notes = app.userDb.notes(bookId, pageId) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text("تعليقاتي على هذه الصفحة", style = MaterialTheme.typography.titleMedium)
            notes.forEach { n ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(n.text, Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { app.userDb.deleteNote(n.id); notes = app.userDb.notes(bookId, pageId) } }) { Text("حذف") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = { Text("تعليق جديد…") }, minLines = 2)
            Button(onClick = { if (text.isNotBlank()) scope.launch { app.userDb.addNote(bookId, pageId, text.trim()); text = ""; notes = app.userDb.notes(bookId, pageId) } }, modifier = Modifier.padding(top = 8.dp)) { Text("حفظ") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** ورقة اختيار صوت القراءة: قائمة الأصوات العربية المتاحة (الرجالية أولًا) مع معاينة، وغِلَظ الصوت والسرعة. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSheet(app: App, onDismiss: () -> Unit) {
    val settings by app.settings.state.collectAsState()
    val voices by app.speaker.voices.collectAsState()
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("صوت القارئ", style = MaterialTheme.typography.titleMedium)
            // ---- القارئ السحابي عالي الجودة ----
            val vscope = rememberCoroutineScope()
            var cloudErr by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("القارئ الإذاعي السحابي (الجودة القصوى)", style = MaterialTheme.typography.titleSmall)
                    Text("أصوات عصبية فصيحة كقارئ المواقع الإخبارية، بجودة ١٩٢ ك.ب/ث — يحتاج اتصالًا ومفتاحًا مجانيًّا", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.useCloudTts, onCheckedChange = { c -> app.settings.update { it.copy(useCloudTts = c) } })
            }
            if (settings.useCloudTts) {
                androidx.compose.material3.OutlinedTextField(value = settings.cloudTtsKey, onValueChange = { v -> app.settings.update { it.copy(cloudTtsKey = v) } }, label = { Text("المفتاح (Azure Speech Key)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                androidx.compose.material3.OutlinedTextField(value = settings.cloudTtsRegion, onValueChange = { v -> app.settings.update { it.copy(cloudTtsRegion = v) } }, label = { Text("المنطقة (مثل westeurope)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 4.dp)) {
                    org.murabbie.shamela.data.CLOUD_VOICES.forEach { (id, label) ->
                        org.murabbie.shamela.ui.Chip(label, settings.cloudTtsVoice == id) { app.settings.update { it.copy(cloudTtsVoice = id) } }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedButton(onClick = {
                        cloudErr = ""
                        vscope.launch { cloudErr = app.speaker.previewCloud(org.murabbie.shamela.data.CloudTts(settings.cloudTtsKey.trim(), settings.cloudTtsRegion.trim(), settings.cloudTtsVoice)) ?: "" }
                    }, enabled = settings.cloudTtsKey.isNotBlank()) { Text("جرّب الصوت السحابي") }
                }
                if (cloudErr.isNotBlank()) Text(cloudErr, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("المفتاح المجاني: حساب Azure ← إنشاء مورد Speech ← انسخ المفتاح والمنطقة (الطبقة المجانية ٥٠٠ ألف حرف شهريًّا). يُرسَل نص الصفحة المقروءة إلى خادم Microsoft ليُحوَّل صوتًا، والمفتاح يُحفظ على جهازك فقط، وما قُرئ مرة يُخزَّن فلا يُستهلك مرتين.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            Text("أو صوت الجهاز: الأصوات الرجالية تُعرض أولًا. اضغط ▶ لسماع عيّنة. خفض «غِلَظ الصوت» يجعل النطق أعمق. الأصوات «عبر الشبكة» أعلى جودة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            if (voices.isEmpty()) Text("لا أصوات عربية متاحة بعد — إن ظهر هذا دائمًا فثبّت العربية في محرك النطق من إعدادات الجهاز.", style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(260.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { app.settings.update { it.copy(ttsVoice = "") } }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = settings.ttsVoice.isEmpty(), onClick = { app.settings.update { it.copy(ttsVoice = "") } })
                        Text("تلقائي (أفضل صوت رجالي متاح)", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                items(voices.size) { i ->
                    val v = voices[i]
                    Row(Modifier.fillMaxWidth().clickable { app.settings.update { it.copy(ttsVoice = v.name) } }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = settings.ttsVoice == v.name, onClick = { app.settings.update { it.copy(ttsVoice = v.name) } })
                        Text(v.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { app.speaker.pitch = settings.ttsPitch; app.speaker.rate = settings.ttsRate; app.speaker.preview(v.name) }) { Icon(Icons.Default.PlayArrow, contentDescription = "معاينة") }
                    }
                }
            }
            Text("غِلَظ الصوت", style = MaterialTheme.typography.labelLarge)
            Slider(value = settings.ttsPitch, onValueChange = { p -> app.settings.update { it.copy(ttsPitch = p) }; app.speaker.pitch = p }, valueRange = 0.5f..1.3f)
            Row {
                androidx.compose.material3.OutlinedButton(onClick = { app.speaker.pitch = settings.ttsPitch; app.speaker.rate = settings.ttsRate; app.speaker.preview(settings.ttsVoice.ifEmpty { voices.firstOrNull()?.name ?: "" }) }) { Text("استمع لعيّنة") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onDismiss) { Text("تم") }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
