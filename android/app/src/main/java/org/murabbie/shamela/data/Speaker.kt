package org.murabbie.shamela.data

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** حالة القراءة الصوتية */
data class SpeakState(val playing: Boolean = false, val available: Boolean = true, val message: String = "", val chunk: Int = 0, val chunks: Int = 0)

/**
 * خدمة قراءة الكتاب صوتيًا بمحرك النطق في أندرويد (TextToSpeech) بصوت عربي:
 * تقرأ نص الصفحة مقطعًا مقطعًا، وعند انتهاء الصفحة تستدعي [onPageDone] لينتقل القارئ إلى الصفحة التالية ويكمل.
 */
class Speaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val _state = MutableStateFlow(SpeakState())
    val state: StateFlow<SpeakState> = _state
    var rate: Float = 1.0f
        set(v) { field = v; tts?.setSpeechRate(v) }
    var onPageDone: (() -> Unit)? = null

    private var queue: List<String> = emptyList()
    private var index = 0
    private var session = 0

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
            t.setSpeechRate(rate)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) { next(id) }
                override fun onDone(id: String?) { next(id) }
            })
            ready = true
            onReady()
        }
    }

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

    /** اقرأ هذا النص (صفحة) من أوله */
    fun speak(text: String) {
        ensure {
            session++
            queue = split(text); index = 0
            if (queue.isEmpty()) { onPageDone?.invoke(); return@ensure }
            speakChunk()
        }
    }

    fun pause() { tts?.stop(); _state.value = _state.value.copy(playing = false) }
    fun resume() { if (index < queue.size) speakChunk() }
    fun stop() { session++; tts?.stop(); queue = emptyList(); index = 0; _state.value = SpeakState(available = _state.value.available, message = _state.value.message) }
    fun shutdown() { stop(); tts?.shutdown(); tts = null; ready = false }

    /** تقسيم النص إلى مقاطع تحت حدّ المحرك (٤٠٠٠ حرف) على حدود الجمل */
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
}
