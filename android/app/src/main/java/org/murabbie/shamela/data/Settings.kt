package org.murabbie.shamela.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.murabbie.shamela.BuildConfig

data class AppSettings(
    val manifestUrl: String = BuildConfig.DEFAULT_MANIFEST_URL,
    val fontScale: Float = 1.0f,
    val readerFontSize: Int = 20,        // بالنقاط
    val readerLineHeight: Float = 1.8f,
    val readerFont: Int = 0,             // 0 افتراضي، 1 serif، 2 sans
    val showTashkeel: Boolean = true,
    val showFootnotes: Boolean = true,
    val keepScreenOn: Boolean = true,
    val themeMode: Int = 0,              // 0 نظام، 1 فاتح، 2 داكن، 3 ورقي (سيبيا)
    val wifiOnlyDownloads: Boolean = false,
    val autoUpdateCheck: Boolean = true,
    val searchMode: Int = 0,             // 0 كل الكلمات، 1 أي كلمة، 2 عبارة
    val searchPrefix: Boolean = false,
    val searchInFoot: Boolean = true,
    val searchInTitles: Boolean = false,
    val searchByRoot: Boolean = false,
    val autoTashkeel: Boolean = false,   // تشكيل آلي للنص غير المشكول (يحتاج معجم التشكيل)
    val ttsRate: Float = 1.0f,          // سرعة القراءة الصوتية
    val ttsPitch: Float = 0.85f,        // غِلَظ الصوت (طبقة النطق؛ أقل = أعمق)
    val ttsVoice: String = "",          // اسم الصوت المختار؛ فارغ = تلقائي (أفضل صوت رجالي متاح)
    val lastManifestCheck: Long = 0,
    val dataVersion: String = "",
    val storageExternal: Boolean = false,
    val libraryTab: Int = 0,
    val nasBase: String = "",   // عنوان بديل للخادم (مثل عنوان الشبكة الداخلية)؛ فارغ = الافتراضي
)

class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state
    val value: AppSettings get() = _state.value

    private fun load(): AppSettings = AppSettings(
        // يُحفظ تحت مفتاح جديد حتى لا تُورَّث قيمة قديمة من إصدار سابق؛ الفارغ = الافتراضي المضمَّن
        manifestUrl = (prefs.getString("manifestUrlCustom", "") ?: "").ifBlank { BuildConfig.DEFAULT_MANIFEST_URL },
        fontScale = prefs.getFloat("fontScale", 1.0f),
        readerFontSize = prefs.getInt("readerFontSize", 20),
        readerLineHeight = prefs.getFloat("readerLineHeight", 1.8f),
        readerFont = prefs.getInt("readerFont", 0),
        showTashkeel = prefs.getBoolean("showTashkeel", true),
        showFootnotes = prefs.getBoolean("showFootnotes", true),
        keepScreenOn = prefs.getBoolean("keepScreenOn", true),
        themeMode = prefs.getInt("themeMode", 0),
        wifiOnlyDownloads = prefs.getBoolean("wifiOnlyDownloads", false),
        autoUpdateCheck = prefs.getBoolean("autoUpdateCheck", true),
        searchMode = prefs.getInt("searchMode", 0),
        searchPrefix = prefs.getBoolean("searchPrefix", false),
        searchInFoot = prefs.getBoolean("searchInFoot", true),
        searchInTitles = prefs.getBoolean("searchInTitles", false),
        searchByRoot = prefs.getBoolean("searchByRoot", false),
        autoTashkeel = prefs.getBoolean("autoTashkeel", false),
        ttsRate = prefs.getFloat("ttsRate", 1.0f),
        ttsPitch = prefs.getFloat("ttsPitch", 0.85f),
        ttsVoice = prefs.getString("ttsVoice", "") ?: "",
        lastManifestCheck = prefs.getLong("lastManifestCheck", 0),
        dataVersion = prefs.getString("dataVersion", "") ?: "",
        storageExternal = prefs.getBoolean("storageExternal", false),
        libraryTab = prefs.getInt("libraryTab", 0),
        nasBase = prefs.getString("nasBase", "") ?: "",
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val s = block(_state.value)
        _state.value = s
        prefs.edit()
            .putString("manifestUrlCustom", if (s.manifestUrl == BuildConfig.DEFAULT_MANIFEST_URL) "" else s.manifestUrl)
            .putFloat("fontScale", s.fontScale)
            .putInt("readerFontSize", s.readerFontSize)
            .putFloat("readerLineHeight", s.readerLineHeight)
            .putInt("readerFont", s.readerFont)
            .putBoolean("showTashkeel", s.showTashkeel)
            .putBoolean("showFootnotes", s.showFootnotes)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putInt("themeMode", s.themeMode)
            .putBoolean("wifiOnlyDownloads", s.wifiOnlyDownloads)
            .putBoolean("autoUpdateCheck", s.autoUpdateCheck)
            .putInt("searchMode", s.searchMode)
            .putBoolean("searchPrefix", s.searchPrefix)
            .putBoolean("searchInFoot", s.searchInFoot)
            .putBoolean("searchInTitles", s.searchInTitles)
            .putBoolean("searchByRoot", s.searchByRoot)
            .putBoolean("autoTashkeel", s.autoTashkeel)
            .putFloat("ttsRate", s.ttsRate)
            .putFloat("ttsPitch", s.ttsPitch)
            .putString("ttsVoice", s.ttsVoice)
            .putLong("lastManifestCheck", s.lastManifestCheck)
            .putString("dataVersion", s.dataVersion)
            .putBoolean("storageExternal", s.storageExternal)
            .putInt("libraryTab", s.libraryTab)
            .putString("nasBase", s.nasBase)
            .apply()
    }
}
