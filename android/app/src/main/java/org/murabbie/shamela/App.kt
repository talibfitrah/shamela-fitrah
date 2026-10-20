package org.murabbie.shamela

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.murabbie.shamela.data.BookReader
import org.murabbie.shamela.data.BookStore
import org.murabbie.shamela.data.DownloadService
import org.murabbie.shamela.data.NewBooks
import org.murabbie.shamela.data.Services
import org.murabbie.shamela.data.Tashkeel
import org.murabbie.shamela.data.Speaker
import org.murabbie.shamela.data.Downloads
import org.murabbie.shamela.data.Library
import org.murabbie.shamela.data.Settings
import org.murabbie.shamela.data.UserDb

class App : Application() {

    lateinit var settings: Settings; private set
    lateinit var library: Library; private set
    lateinit var userDb: UserDb; private set
    lateinit var downloads: Downloads; private set
    lateinit var newBooks: NewBooks; private set
    lateinit var backup: org.murabbie.shamela.data.Backup; private set
    lateinit var services: Services; private set
    lateinit var tashkeel: Tashkeel; private set
    val speaker: Speaker by lazy { Speaker(this) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        library = Library(this, settings)
        userDb = UserDb(this)
        downloads = Downloads(this, library, settings)
        newBooks = NewBooks(library, userDb)
        backup = org.murabbie.shamela.data.Backup(this, library, settings, userDb)
        downloads.backup = backup
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { if (backup.available()) backup.listBackedUp() } }
        services = Services(library)
        tashkeel = Tashkeel(library)
        downloads.onActivity = { active -> if (active) DownloadService.start(this) }
        downloads.onWebImported = { id, file ->
            val meta = runCatching { BookReader(id, file).meta() }.getOrDefault(emptyMap())
            if (library.book(id) == null) userDb.addExtraBook(id, meta)
        }
        createChannels()
        // بعد تحديث الفهرس من NAS: أسقط من المستجدات ما صار في الفهرس
        scope.launch { library.state.collect { if (it is org.murabbie.shamela.data.LibraryState.Ready) runCatching { newBooks.pruneIndexed() } } }
        // فحص التحديثات مرة يوميًا
        scope.launch {
            val s = settings.value
            if (s.autoUpdateCheck && System.currentTimeMillis() - s.lastManifestCheck > 24 * 3600 * 1000L) runCatching { library.fetchManifest() }
        }
    }

    /** كتاب من الفهرس أو من الكتب الإضافية (المجلوبة من الموقع) */
    suspend fun book(id: Int) = library.book(id) ?: userDb.extraBook(id)

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DOWNLOADS, getString(R.string.notification_channel_downloads), NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        lateinit var instance: App; private set
    }
}
