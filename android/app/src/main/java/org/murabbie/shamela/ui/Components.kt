package org.murabbie.shamela.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.murabbie.shamela.data.ArabicText
import org.murabbie.shamela.data.Book
import org.murabbie.shamela.data.BookState
import org.murabbie.shamela.data.DownloadJob
import org.murabbie.shamela.data.JobStatus

fun bookState(installed: Boolean, job: DownloadJob?, book: Book, queued: Boolean = false, onPhone: Boolean = false): BookState = when {
    installed -> BookState.INSTALLED
    job?.status == JobStatus.DOWNLOADING || job?.status == JobStatus.INSTALLING -> BookState.DOWNLOADING
    job?.status == JobStatus.QUEUED || queued -> BookState.QUEUED
    onPhone -> BookState.ON_PHONE   // محفوظ في مجلد الحفظ على الهاتف — يُستعاد بلا إنترنت
    else -> BookState.NOT_DOWNLOADED
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookRow(
    book: Book,
    state: BookState,
    job: DownloadJob? = null,
    showAuthor: Boolean = true,
    showCategory: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(book.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = buildString {
                    if (showAuthor && book.authorName.isNotBlank()) append(book.authorName)
                    if (showCategory && book.categoryName.isNotBlank()) { if (isNotEmpty()) append(" · "); append(book.categoryName) }
                    if (book.pagesCount > 0) { if (isNotEmpty()) append(" · "); append(ArabicText.arabicDigits(book.pagesCount)).append(" ص") }
                    if (book.file != null && book.file.size > 0) { append(" · ").append(ArabicText.formatSize(book.file.length)) }
                }
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            when (state) {
                BookState.INSTALLED -> Icon(Icons.Default.CheckCircle, contentDescription = "مثبَّت", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
                BookState.DOWNLOADING, BookState.QUEUED -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                BookState.NOT_DOWNLOADED -> if (onDownload != null) IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                    Icon(if (book.file != null && book.file.pack.isNotBlank()) Icons.Default.CloudDownload else Icons.Default.Language, contentDescription = "تنزيل", tint = MaterialTheme.colorScheme.primary)
                }
                BookState.ON_PHONE -> if (onDownload != null) IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.SettingsBackupRestore, contentDescription = "محفوظ على الهاتف — استعادة بلا إنترنت", tint = MaterialTheme.colorScheme.tertiary)
                } else Icon(Icons.Default.SettingsBackupRestore, contentDescription = "محفوظ على الهاتف", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
                BookState.UNAVAILABLE -> {}
            }
        }
        if (job != null && (job.status == JobStatus.DOWNLOADING || job.status == JobStatus.INSTALLING)) {
            Spacer(Modifier.height(6.dp))
            val p = if (job.total > 0) (job.bytes.toFloat() / job.total).coerceIn(0f, 1f) else null
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            if (job.message.isNotBlank()) Text(job.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (job?.status == JobStatus.ERROR) {
            Text("تعذر التنزيل: ${job.message}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onSearch: (() -> Unit)? = null) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) { Icon(Icons.Default.Close, contentDescription = "مسح") } },
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch?.invoke() }),
    )
}

/** نص مع تظليل مواضع */
fun highlighted(text: String, ranges: List<IntRange>, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    for (r in ranges) {
        if (r.first < 0 || r.last >= text.length || r.first > r.last) continue
        addStyle(SpanStyle(background = color, fontWeight = FontWeight.Bold), r.first, r.last + 1)
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(key, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.padding(end = 6.dp).background(bg, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}
