package org.murabbie.shamela.data

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** حالة القراءة الصوتية */
data class SpeakState(val playing: Boolean = false, val available: Boolean = true, val message: String = "", val chunk: Int = 0, val chunks: Int = 0)

/** صوت متاح للقراءة (محرك الجهاز) */
data class VoiceInfo(val name: String, val label: String, val network: Boolean, val quality: Int, val maleLikely: Boolean)

/** إعداد القارئ السحابي (أصوات عصبية بجودة إذاعية — مثل قارئ المواقع الإخبارية) */
data class CloudTts(val key: String, val region: String, val voice: String)

/** الأصوات العربية الرجالية العصبية المتاحة في القارئ السحابي */
val CLOUD_VOICES = listOf(
    "ar-SA-HamedNeural" to "حامد (فصحى سعودية)",
    "ar-EG-ShakirNeural" to "شاكر (مصر)",
    "ar-AE-HamdanNeural" to "حمدان (الإمارات)",
    "ar-JO-TaimNeural" to "تيم (الأردن)",
    "ar-QA-MoazNeural" to "معاذ (قطر)",
    "ar-SY-LaithNeural" to "ليث (الشام)",
    "ar-KW-FahedNeural" to "فهد (الكويت)",
    "ar-MA-JamalNeural" to "جمال (المغرب)",
)

/**
 * خدمة قراءة الكتاب صوتيًّا، بمحرِّكين:
 * ١) القارئ السحابي (إن ضُبط مفتاحه): أصوات عصبية بجودة إذاعية قصوى (MP3 بمعدل ١٩٢ ك.ب/ث) من أصوات رجالية
 *    فصيحة — الطبقة نفسها التي تعتمدها كبرى المواقع الإخبارية في قارئها الصوتي — مع خزن المقاطع المُنتَجة
 *    حتى لا يُعاد توليد ما قُرئ من قبل.
 * ٢) محرك النطق في الجهاز (TextToSpeech) احتياطًا وعند غياب المفتاح أو الشبكة.
 * في الحالين: قراءة الصفحة مقطعًا مقطعًا، وعند انتهائها يُستدعى [onPageDone] فينتقل القارئ إلى الصفحة التالية.
 */
class Speaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val _state = MutableStateFlow(SpeakState())
    val state: StateFlow<SpeakState> = _state
    private val _voices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    val voices: StateFlow<List<VoiceInfo>> = _voices

    var rate: Float = 1.0f
        set(v) { field = v; tts?.setSpeechRate(v); runCatching { player?.let { if (it.isPlaying) it.playbackParams = PlaybackParams().setSpeed(v) } } }
    var pitch: Float = 0.85f
        set(v) { field = v; tts?.setPitch(v) }
    /** اسم صوت الجهاز المختار (فارغ = الاختيار التلقائي للصوت الرجالي) */
    var voiceName: String = ""
        set(v) { field = v; if (ready) applyVoice() }
    /** إعداد القارئ السحابي؛ null = استعمال محرك الجهاز */
    @Volatile var cloud: CloudTts? = null
    var onPageDone: (() -> Unit)? = null

    private var queue: List<String> = emptyList()
    private var index = 0
    private var session = 0

    // ---------- القارئ السحابي ----------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cloudJob: Job? = null
    private var player: MediaPlayer? = null
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val cacheDir: File get() = File(context.cacheDir, "tts").apply { mkdirs() }

    private fun ssmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** توليد مقطع صوتي (مع خزن) — يعيد ملف MP3 */
    private suspend fun synth(c: CloudTts, text: String): File = withContext(Dispatchers.IO) {
        val h = MessageDigest.getInstance("MD5").digest((c.voice + "|" + text).toByteArray()).joinToString("") { "%02x".format(it) }
        val f = File(cacheDir, "$h.mp3")
        if (f.exists() && f.length() > 0) return@withContext f
        val ssml = """<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="ar-SA"><voice name="${c.voice}">${ssmlEscape(text)}</voice></speak>"""
        val req = Request.Builder()
            .url("https://${c.region}.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", c.key)
            .header("X-Microsoft-OutputFormat", "audio-48khz-192kbitrate-mono-mp3")
            .header("User-Agent", "Shamela-Android")
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException(when (resp.code) {
                401, 403 -> "مفتاح القارئ السحابي غير صحيح أو المنطقة خطأ (رمز ${resp.code})"
                429 -> "تجاوزتَ حدّ الاستخدام المجاني للقارئ السحابي هذا الشهر"
                else -> "تعذّر القارئ السحابي (رمز ${resp.code})"
            })
            val tmp = File(cacheDir, "$h.part")
            resp.body!!.byteStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            if (tmp.length() < 128) { tmp.delete(); throw java.io.IOException("ردّ صوتي فارغ من الخادم") }
            tmp.renameTo(f)
        }
        // تهذيب الخزن: ١٥٠ م.ب كحد أقصى
        runCatching {
            val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
            var total = files.sumOf { it.length() }
            for (old in files) { if (total <= 150L * 1024 * 1024) break; total -= old.length(); old.delete() }
        }
        f
    }

    private suspend fun playFile(f: File) {
        suspendCancellableCoroutine { cont ->
            val p = MediaPlayer()
            player?.release(); player = p
            p.setDataSource(f.path)
            p.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
            p.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
            p.prepare()
            runCatching { p.playbackParams = PlaybackParams().setSpeed(rate) }
            p.start()
            cont.invokeOnCancellation { runCatching { p.stop(); p.release() }; if (player === p) player = null }
        }
    }

    private fun speakCloud(c: CloudTts, text: String) {
        val s = ++session
        cloudJob?.cancel(); tts?.stop()
        val chunks = split(text, 1800)
        if (chunks.isEmpty()) { onPageDone?.invoke(); return }
        cloudJob = scope.launch {
            try {
                // توليد المقطع الأول، ثم توليد التالي في أثناء تشغيل الحالي
                var next: File? = synth(c, chunks[0])
                for (i in chunks.indices) {
                    if (s != session) return@launch
                    val cur = next ?: synth(c, chunks[i])
                    val prefetch = if (i + 1 < chunks.size) scope.launch(Dispatchers.IO) { runCatching { synth(c, chunks[i + 1]) } } else null
                    _state.value = SpeakState(playing = true, chunk = i + 1, chunks = chunks.size)
                    playFile(cur)
                    prefetch?.join()
                    next = if (i + 1 < chunks.size) runCatching { synth(c, chunks[i + 1]) }.getOrNull() else null
                }
                if (s == session) { _state.value = _state.value.copy(playing = true); onPageDone?.invoke() }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                // تعذّر السحابي: رسالة ثم متابعة بمحرك الجهاز
                _state.value = _state.value.copy(message = (e.message ?: "تعذّر القارئ السحابي") + " — تابعتُ بصوت الجهاز")
                if (s == session) speakDevice(text)
            }
        }
    }

    // ---------- محرك الجهاز ----------

    private fun maleLikely(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("male") && !n.contains("female") || n.contains("-arz") || n.contains("-ard") || n.contains("#male")
    }

    private fun refreshVoices() {
        val t = tts ?: return
        val list = runCatching { t.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "ar" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<Voice> { maleLikely(it.name) }.thenByDescending { it.quality }.thenBy { it.isNetworkConnectionRequired })
        var i = 0
        _voices.value = list.map { v ->
            i++
            val q = when { v.quality >= Voice.QUALITY_VERY_HIGH -> "جودة عالية جدًّا"; v.quality >= Voice.QUALITY_HIGH -> "جودة عالية"; else -> "جودة عادية" }
            VoiceInfo(v.name, "صوت ${ArabicText.arabicDigits(i)}" + (if (maleLikely(v.name)) " — رجالي" else "") + " ($q" + (if (v.isNetworkConnectionRequired) "، عبر الشبكة" else "") + ")", v.isNetworkConnectionRequired, v.quality, maleLikely(v.name))
        }
    }

    private fun applyVoice() {
        val t = tts ?: return
        val all = runCatching { t.voices }.getOrNull().orEmpty().filter { it.locale.language == "ar" }
        val v = all.firstOrNull { it.name == voiceName }
            ?: all.filter { maleLikely(it.name) }.maxByOrNull { it.quality * 10 + (if (it.isNetworkConnectionRequired) 0 else 1) }
            ?: all.maxByOrNull { it.quality }
        if (v != null) runCatching { t.voice = v }
        t.setPitch(pitch); t.setSpeechRate(rate)
    }

    private fun ensure(onReady: () -> Unit) {
        if (ready) { onReady(); return }
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) { _state.value = SpeakState(available = false, message = "محرك النطق غير متوفر على هذا الجهاز"); return@TextToSpeech }
            val t = tts!!
            val r = t.setLanguage(Locale("ar"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                _state.value = SpeakState(available = false, message = "لا صوت عربي مثبَّت. ثبّت بيانات اللغة العربية لمحرك النطق (إعدادات الجهاز ← اللغة ← تحويل النص إلى كلام ← Google ← تثبيت العربية).")
                return@TextToSpeech
            }
            refreshVoices()
            applyVoice()
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) { next(id) }
                override fun onDone(id: String?) { next(id) }
            })
            ready = true
            onReady()
        }
    }

    /** تهيئة المحرك مبكرًا (لعرض قائمة الأصوات) */
    fun warmup(onReady: () -> Unit = {}) = ensure(onReady)

    private fun next(id: String?) {
        val s = session
        if (id == null || !id.startsWith("s$s-")) return
        index++
        if (index < queue.size) speakChunk() else { _state.value = _state.value.copy(playing = true, chunk = queue.size); onPageDone?.invoke() }
    }

    private fun speakChunk() {
        val t = tts ?: return
        if (index >= queue.size) return
        _state.value = SpeakState(playing = true, chunk = index + 1, chunks = queue.size)
        t.speak(queue[index], TextToSpeech.QUEUE_FLUSH, Bundle(), "s$session-$index")
    }

    private fun speakDevice(text: String) {
        ensure {
            session++
            queue = split(text); index = 0
            if (queue.isEmpty()) { onPageDone?.invoke(); return@ensure }
            speakChunk()
        }
    }

    /** اقرأ هذا النص (صفحة) من أوله — بالقارئ السحابي إن ضُبط، وإلا بمحرك الجهاز */
    fun speak(text: String) {
        val c = cloud
        if (c != null && c.key.isNotBlank()) speakCloud(c, text) else speakDevice(text)
    }

    /** معاينة صوت من أصوات الجهاز */
    fun preview(name: String, sample: String = SAMPLE) {
        ensure {
            val t = tts ?: return@ensure
            runCatching { t.voices }.getOrNull().orEmpty().firstOrNull { it.name == name }?.let { runCatching { t.voice = it } }
            t.setPitch(pitch); t.setSpeechRate(rate)
            t.speak(sample, TextToSpeech.QUEUE_FLUSH, Bundle(), "preview")
        }
    }

    /** معاينة صوت سحابي — يعيد null عند النجاح أو نص الخطأ */
    suspend fun previewCloud(c: CloudTts, sample: String = SAMPLE): String? = try {
        cloudJob?.cancel(); tts?.stop(); session++
        val f = synth(c, sample)
        withContext(Dispatchers.Main) { scope.launch { playFile(f) } }
        null
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        e.message ?: "تعذّرت المعاينة"
    }

    /** إعادة الصوت المختار بعد المعاينة */
    fun endPreview() { tts?.stop(); runCatching { player?.stop() }; if (ready) applyVoice() }

    fun pause() { tts?.stop(); runCatching { player?.pause() }; _state.value = _state.value.copy(playing = false) }
    fun resume() {
        val p = player
        if (p != null) { runCatching { p.start(); _state.value = _state.value.copy(playing = true) } }
        else if (index < queue.size) speakChunk()
    }
    fun stop() {
        session++
        cloudJob?.cancel(); cloudJob = null
        runCatching { player?.stop(); player?.release() }; player = null
        tts?.stop(); queue = emptyList(); index = 0
        _state.value = SpeakState(available = _state.value.available, message = "")
    }
    fun shutdown() { stop(); tts?.shutdown(); tts = null; ready = false }

    /** تقسيم النص إلى مقاطع على حدود الجمل */
    private fun split(text: String, max: Int = 3000): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val sentences = clean.split(Regex("(?<=[.!؟:؛\\n])\\s+"))
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.length + s.length + 1 > max && sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
            if (s.length > max) { var i = 0; while (i < s.length) { out.add(s.substring(i, minOf(s.length, i + max))); i += max } }
            else { if (sb.isNotEmpty()) sb.append(' '); sb.append(s) }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    companion object {
        const val SAMPLE = "قَالَ رَسُولُ اللَّهِ صَلَّى اللَّهُ عَلَيْهِ وَسَلَّمَ: إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ، وَإِنَّمَا لِكُلِّ امْرِئٍ مَا نَوَى."
    }
}
