package org.murabbie.shamela.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.murabbie.shamela.App
import org.murabbie.shamela.data.ArabicText
import org.murabbie.shamela.data.LibraryState

/** شاشة أول تشغيل: تنزيل فهرس المكتبة (master.db) تلقائيًا من NAS. */
@Composable
fun SetupScreen(app: App) {
    val state by app.library.state.collectAsState()
    val settings by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showAdvanced by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(settings.manifestUrl) }
    var base by remember { mutableStateOf(settings.nasBase) }
    var started by remember { mutableStateOf(false) }
    val diag by org.murabbie.shamela.data.NasHttp.status.collectAsState()

    LaunchedEffect(state) {
        if (state is LibraryState.NotInstalled && !started) { started = true; app.library.installMaster() }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("المكتبة الشاملة", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("نسخة الأندرويد — البيانات من خادم NAS", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        when (val s = state) {
            is LibraryState.Checking -> { CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text("فحص البيانات…") }
            is LibraryState.Downloading -> {
                Text(s.message, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                if (s.total > 0) {
                    LinearProgressIndicator(progress = { (s.bytes.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text("${ArabicText.formatSize(s.bytes)} / ${ArabicText.formatSize(s.total)}", style = MaterialTheme.typography.labelMedium)
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (diag.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(diag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth()) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showAdvanced = !showAdvanced }) { Text("خيارات الخادم") }
            }
            is LibraryState.Error -> {
                Text("تعذر تنزيل فهرس المكتبة", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(6.dp))
                Text(s.message, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                if (diag.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(diag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth()) }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { scope.launch { app.library.installMaster() } }) { Text("إعادة المحاولة") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showAdvanced = !showAdvanced }) { Text("خيارات الخادم") }
            }
            is LibraryState.NotInstalled -> {
                Text("يحتاج التطبيق إلى تنزيل فهرس المكتبة (نحو ٣ م.ب) مرة واحدة، ثم تُنزَّل الكتب التي تختارها عند الحاجة.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { scope.launch { app.library.installMaster() } }) { Text("ابدأ") }
            }
            is LibraryState.Ready -> {}
        }
        if (showAdvanced) {
            Spacer(Modifier.height(16.dp))
            // نسخة play: HTTPS فقط، فلا معنى لعنوان شبكة داخلية
            if (!org.murabbie.shamela.BuildConfig.PLAY_STORE) {
            Text("إن كنت على شبكة البيت نفسها مع NAS فقد لا يصل الهاتف إلى عنوانه العام؛ أدخل عنوان NAS الداخلي (مثل 192.168.1.20:5000):", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = base, onValueChange = { base = it }, label = { Text("عنوان الخادم البديل (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("رابط manifest.json") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = { app.settings.update { it.copy(manifestUrl = url.trim(), nasBase = base.trim()) }; scope.launch { app.library.installMaster() } }) { Text("حفظ وإعادة المحاولة") }
        }
    }
}
