package org.murabbie.shamela.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * غلاف بسيط حول اتصال SQLite المدمج (androidx.sqlite bundled — يحوي FTS5) يسلسل الوصول عبر Mutex.
 */
class Db(val path: String, readOnly: Boolean) : AutoCloseable {

    private val driver = BundledSQLiteDriver()
    private val conn: SQLiteConnection = driver.open(path)
    private val mutex = Mutex()
    @Volatile var closed = false
        private set

    init {
        if (readOnly) runCatching { conn.execSQL("PRAGMA query_only = 1") }
        runCatching { conn.execSQL("PRAGMA cache_size = -8000") }
        runCatching { conn.execSQL("PRAGMA temp_store = MEMORY") }
        if (!readOnly) runCatching { conn.execSQL("PRAGMA journal_mode = WAL") }
    }

    suspend fun <T> query(sql: String, vararg args: Any?, mapper: (Row) -> T): List<T> = withContext(Dispatchers.IO) {
        mutex.withLock {
            conn.prepare(sql).use { st ->
                bind(st, args)
                val out = ArrayList<T>()
                val row = Row(st)
                while (st.step()) out.add(mapper(row))
                out
            }
        }
    }

    suspend fun <T> queryOne(sql: String, vararg args: Any?, mapper: (Row) -> T): T? = withContext(Dispatchers.IO) {
        mutex.withLock {
            conn.prepare(sql).use { st ->
                bind(st, args)
                val row = Row(st)
                if (st.step()) mapper(row) else null
            }
        }
    }

    suspend fun count(sql: String, vararg args: Any?): Long = queryOne(sql, *args) { it.long(0) } ?: 0L

    suspend fun exec(sql: String, vararg args: Any?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            conn.prepare(sql).use { st ->
                bind(st, args)
                while (st.step()) { }
            }
        }
    }

    /** تنفيذ عدة أوامر داخل معاملة واحدة (مع القفل). */
    suspend fun transaction(block: suspend (Tx) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            conn.execSQL("BEGIN")
            try {
                block(Tx())
                conn.execSQL("COMMIT")
            } catch (e: Throwable) {
                runCatching { conn.execSQL("ROLLBACK") }
                throw e
            }
        }
    }

    inner class Tx {
        fun exec(sql: String, vararg args: Any?) {
            conn.prepare(sql).use { st -> bind(st, args); while (st.step()) { } }
        }
        fun <T> query(sql: String, vararg args: Any?, mapper: (Row) -> T): List<T> {
            conn.prepare(sql).use { st ->
                bind(st, args)
                val out = ArrayList<T>(); val row = Row(st)
                while (st.step()) out.add(mapper(row))
                return out
            }
        }
    }

    private fun bind(st: SQLiteStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> st.bindNull(idx)
                is Int -> st.bindLong(idx, a.toLong())
                is Long -> st.bindLong(idx, a)
                is Boolean -> st.bindLong(idx, if (a) 1 else 0)
                is Double -> st.bindDouble(idx, a)
                is Float -> st.bindDouble(idx, a.toDouble())
                is ByteArray -> st.bindBlob(idx, a)
                else -> st.bindText(idx, a.toString())
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { conn.close() }
    }

    /** إغلاق بعد انتظار أي استعلام جارٍ */
    suspend fun closeSuspend() {
        closed = true
        mutex.withLock { runCatching { conn.close() } }
    }

    class Row(private val st: SQLiteStatement) {
        fun long(i: Int): Long = if (st.isNull(i)) 0L else st.getLong(i)
        fun int(i: Int): Int = long(i).toInt()
        fun intOrNull(i: Int): Int? = if (st.isNull(i)) null else st.getLong(i).toInt()
        fun text(i: Int): String = if (st.isNull(i)) "" else st.getText(i)
        fun textOrNull(i: Int): String? = if (st.isNull(i)) null else st.getText(i)
        fun bool(i: Int): Boolean = long(i) != 0L
        fun isNull(i: Int): Boolean = st.isNull(i)
        fun blob(i: Int): ByteArray? = if (st.isNull(i)) null else st.getBlob(i)
    }
}
