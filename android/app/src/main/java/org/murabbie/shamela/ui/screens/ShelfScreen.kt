package org.murabbie.shamela.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import org.murabbie.shamela.data.Book
import org.murabbie.shamela.data.Bookmark
import org.murabbie.shamela.data.HistoryItem
import org.murabbie.shamela.data.Note
import org.murabbie.shamela.ui.EmptyState
import org.murabbie.shamela.ui.go

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(app: App, nav: NavHostController) {
    var tab by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var bookmarks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<Book>>(emptyList()) }
    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        history = app.userDb.history()
        bookmarks = app.userDb.bookmarks()
        favorites = app.library.booksByIds(app.userDb.favoriteIds())
        notes = app.userDb.allNotes()
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("قراءاتي") })
        TabRow(selectedTabIndex = tab) {
            listOf("آخر القراءة", "العلامات", "المفضلة", "التعليقات").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> if (history.isEmpty()) EmptyState("لم تقرأ شيئًا بعد") else LazyColumn {
                items(history, key = { it.bookId }) { h ->
                    Row(Modifier.fillMaxWidth().clickable { nav.go("reader/${h.bookId}/${h.pageId}") }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(h.bookName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(h.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { scope.launch { app.userDb.removeHistory(h.bookId); refresh++ } }) { Text("إزالة") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
            1 -> if (bookmarks.isEmpty()) EmptyState("لا علامات بعد — من شاشة القراءة اضغط أيقونة العلامة") else LazyColumn {
                items(bookmarks, key = { it.id }) { bm ->
                    Row(Modifier.fillMaxWidth().clickable { nav.go("reader/${bm.bookId}/${bm.pageId}") }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(bm.bookName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(bm.label + if (bm.note.isNotBlank()) " — ${bm.note}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { scope.launch { app.userDb.removeBookmark(bm.id); refresh++ } }) { Text("حذف") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
            2 -> if (favorites.isEmpty()) EmptyState("لا كتب مفضلة — من بطاقة الكتاب اضغط القلب") else BookList(app, nav, favorites, showCategory = true)
            3 -> if (notes.isEmpty()) EmptyState("لا تعليقات بعد") else LazyColumn {
                items(notes, key = { it.id }) { n ->
                    var name by remember { mutableStateOf("") }
                    LaunchedEffect(n.bookId) { name = app.book(n.bookId)?.name ?: "كتاب ${n.bookId}" }
                    Column(Modifier.fillMaxWidth().clickable { nav.go("reader/${n.bookId}/${n.pageId}") }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(n.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}
