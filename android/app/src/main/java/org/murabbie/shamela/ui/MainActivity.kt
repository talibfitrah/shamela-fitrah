package org.murabbie.shamela.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import org.murabbie.shamela.App
import org.murabbie.shamela.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    private val pendingOpen = mutableStateOf<String?>(null)
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingOpen.value = routeFrom(intent)
        if (Build.VERSION.SDK_INT >= 33) runCatching { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        val app = application as App
        setContent {
            val settings by app.settings.state.collectAsState()
            LaunchedEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            AppTheme(themeMode = settings.themeMode, fontScale = settings.fontScale) {
                AppRoot(app = app, pendingOpen = pendingOpen)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingOpen.value = routeFrom(intent)
    }

    /** رابط shamela.ws/book/ID[/PAGE] → مسار داخل التطبيق */
    private fun routeFrom(intent: Intent?): String? {
        intent ?: return null
        intent.getStringExtra("open")?.let { return it }
        val data = intent.data ?: return null
        val segs = data.pathSegments
        if (segs.size >= 2 && segs[0] == "book") {
            val id = segs[1].toIntOrNull() ?: return null
            val page = segs.getOrNull(2)?.toIntOrNull()
            return if (page != null) "reader/$id/$page" else "book/$id"
        }
        return null
    }
}
