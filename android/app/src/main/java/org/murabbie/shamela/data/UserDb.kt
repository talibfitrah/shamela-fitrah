package org.murabbie.shamela.data

import android.content.Context
import java.io.File

data class Bookmark(val id: Long, val bookId: Int, val bookName: String, val pageId: Int, val label: String, val note: String, val created: Long)
data class HistoryItem(val bookId: Int, val bookName: String, val pageId: Int, val label: String, val updated: Long)
data class SearchHistory(val query: String, val updated: Long)
data class Note(val id: Long, val bookId: Int, val pageId: Int, val text: String, val created: Long)

/** بيانات المستخدم: العلامات، وسجل القراءة، وسجل البحث، والتعليقات الخاصة. */
class UserDb(context: Context) {
    private val file = File(context.filesDir, "user.db")
    val db = Db(file.path, readOnly = false)

    init {
        kotlinx.coroutines.runBlocking {
            db.exec("CREATE TABLE IF NOT EXISTS bookmark(id INTEGER PRIMARY KEY AUTOINCREMENT, book_id INTEGER, book_name TEXT, page_id INTEGER, label TEXT, note TEXT DEFAULT '', created INTEGER)")
            db.exec("CREATE TABLE IF NOT EXISTS history(book_id INTEGER PRIMARY KEY, book_name TEXT, page_id INTEGER, label TEXT, updated INTEGER)")
            db.exec("CREATE TABLE IF NOT EXISTS search_history(query TEXT PRIMARY KEY, updated INTEGER)")
            db.exec("CREATE TABLE IF NOT EXISTS note(id INTEGER PRIMARY KEY AUTOINCREMENT, book_id INTEGER, page_id INTEGER, text TEXT, created INTEGER)")
            db.exec("CREATE TABLE IF NOT EXISTS favorite_book(book_id INTEGER PRIMARY KEY, created INTEGER)")
            db.exec("CREATE INDEX IF NOT EXISTS note_bp ON note(book_id, page_id)")
            db.exec("CREATE TABLE IF NOT EXISTS extra_book(book_id INTEGER PRIMARY KEY, name TEXT, author_name TEXT, author_id INTEGER, category_name TEXT, card TEXT, pages INTEGER, created INTEGER)")
        }
    }

    suspend fun addBookmark(book: Book, page: Page, note: String = "") {
        db.exec("INSERT INTO bookmark(book_id, book_name, page_id, label, note, created) VALUES(?,?,?,?,?,?)", book.id, book.name, page.id, page.label, note, System.currentTimeMillis())
    }
    suspend fun removeBookmark(id: Long) = db.exec("DELETE FROM bookmark WHERE id=?", id)
    suspend fun removeBookmarkAt(bookId: Int, pageId: Int) = db.exec("DELETE FROM bookmark WHERE book_id=? AND page_id=?", bookId, pageId)
    suspend fun isBookmarked(bookId: Int, pageId: Int): Boolean = db.count("SELECT count(*) FROM bookmark WHERE book_id=? AND page_id=?", bookId, pageId) > 0
    suspend fun bookmarks(bookId: Int? = null): List<Bookmark> =
        if (bookId == null) db.query("SELECT id, book_id, book_name, page_id, label, note, created FROM bookmark ORDER BY created DESC") { Bookmark(it.long(0), it.int(1), it.text(2), it.int(3), it.text(4), it.text(5), it.long(6)) }
        else db.query("SELECT id, book_id, book_name, page_id, label, note, created FROM bookmark WHERE book_id=? ORDER BY page_id", bookId) { Bookmark(it.long(0), it.int(1), it.text(2), it.int(3), it.text(4), it.text(5), it.long(6)) }

    suspend fun setPosition(book: Book, page: Page) {
        db.exec("INSERT OR REPLACE INTO history(book_id, book_name, page_id, label, updated) VALUES(?,?,?,?,?)", book.id, book.name, page.id, page.label, System.currentTimeMillis())
    }
    suspend fun position(bookId: Int): Int? = db.queryOne("SELECT page_id FROM history WHERE book_id=?", bookId) { it.int(0) }
    suspend fun history(limit: Int = 100): List<HistoryItem> = db.query("SELECT book_id, book_name, page_id, label, updated FROM history ORDER BY updated DESC LIMIT ?", limit) { HistoryItem(it.int(0), it.text(1), it.int(2), it.text(3), it.long(4)) }
    suspend fun removeHistory(bookId: Int) = db.exec("DELETE FROM history WHERE book_id=?", bookId)
    suspend fun clearHistory() = db.exec("DELETE FROM history")

    suspend fun addSearch(q: String) { if (q.isNotBlank()) db.exec("INSERT OR REPLACE INTO search_history(query, updated) VALUES(?,?)", q.trim(), System.currentTimeMillis()) }
    suspend fun searches(limit: Int = 30): List<SearchHistory> = db.query("SELECT query, updated FROM search_history ORDER BY updated DESC LIMIT ?", limit) { SearchHistory(it.text(0), it.long(1)) }
    suspend fun clearSearches() = db.exec("DELETE FROM search_history")

    suspend fun addNote(bookId: Int, pageId: Int, text: String) = db.exec("INSERT INTO note(book_id, page_id, text, created) VALUES(?,?,?,?)", bookId, pageId, text, System.currentTimeMillis())
    suspend fun updateNote(id: Long, text: String) = db.exec("UPDATE note SET text=? WHERE id=?", text, id)
    suspend fun deleteNote(id: Long) = db.exec("DELETE FROM note WHERE id=?", id)
    suspend fun notes(bookId: Int, pageId: Int): List<Note> = db.query("SELECT id, book_id, page_id, text, created FROM note WHERE book_id=? AND page_id=? ORDER BY created", bookId, pageId) { Note(it.long(0), it.int(1), it.int(2), it.text(3), it.long(4)) }
    suspend fun allNotes(): List<Note> = db.query("SELECT id, book_id, page_id, text, created FROM note ORDER BY created DESC") { Note(it.long(0), it.int(1), it.int(2), it.text(3), it.long(4)) }

    suspend fun toggleFavorite(bookId: Int): Boolean {
        val has = db.count("SELECT count(*) FROM favorite_book WHERE book_id=?", bookId) > 0
        if (has) db.exec("DELETE FROM favorite_book WHERE book_id=?", bookId) else db.exec("INSERT INTO favorite_book(book_id, created) VALUES(?,?)", bookId, System.currentTimeMillis())
        return !has
    }
    suspend fun isFavorite(bookId: Int): Boolean = db.count("SELECT count(*) FROM favorite_book WHERE book_id=?", bookId) > 0
    suspend fun favoriteIds(): List<Int> = db.query("SELECT book_id FROM favorite_book ORDER BY created DESC") { it.int(0) }

    /** كتب جُلبت من shamela.ws وليست في فهرس NAS بعد */
    suspend fun addExtraBook(id: Int, meta: Map<String, String>) {
        db.exec("INSERT OR REPLACE INTO extra_book(book_id, name, author_name, author_id, category_name, card, pages, created) VALUES(?,?,?,?,?,?,?,?)",
            id, meta["book_name"] ?: "كتاب $id", meta["author_name"] ?: "", meta["author_id"]?.toIntOrNull(), meta["category_name"] ?: "", meta["card"] ?: "", meta["pages"]?.toIntOrNull() ?: 0, System.currentTimeMillis())
    }
    suspend fun extraBook(id: Int): Book? = db.queryOne("SELECT book_id, name, author_name, author_id, category_name, card, pages FROM extra_book WHERE book_id=?", id) { r ->
        Book(r.int(0), r.text(1), 0, 1, 0, listOfNotNull(r.intOrNull(3)), r.intOrNull(3) ?: 0, r.text(2), 0, null, null, org.json.JSONObject().put("card", r.text(5)).toString(), BookFile("", 0, 0, 0, "", r.int(6), 0), r.text(4))
    }
    suspend fun extraBooks(): List<Book> = db.query("SELECT book_id FROM extra_book ORDER BY created DESC") { it.int(0) }.mapNotNull { extraBook(it) }
    suspend fun extraBooksByAuthor(authorId: Int): List<Book> = db.query("SELECT book_id FROM extra_book WHERE author_id=? ORDER BY created DESC", authorId) { it.int(0) }.mapNotNull { extraBook(it) }
    suspend fun removeExtraBook(id: Int) = db.exec("DELETE FROM extra_book WHERE book_id=?", id)
}
