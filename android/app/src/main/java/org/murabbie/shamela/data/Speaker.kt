package org.murabbie.shamela.data

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** حالة القراءة الصوتية */
data class SpeakState(val playing: Boolean = false, val available: Boolean = true, val message: String = "", val chunk: Int = 0, val chunks: Int = 0)

/** صوت عربي في محرك النطق: [name] اسمه عند المحرك، [label] نصّ عرضه في قائمة الاختيار */
data class VoiceInfo(val name: String, val label: String)

/** عيّنة المعاينة: حديث مشكول بالكامل */
private const val PREVIEW_SAMPLE = "قَالَ رَسُولُ اللَّهِ صَلَّى اللَّهُ عَلَيْهِ وَسَلَّمَ: إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ، وَإِنَّمَا لِكُلِّ امْرِئٍ مَا نَوَى."

/**
 * خدمة قراءة الكتاب صوتيًا بمحرك النطق في أندرويد (TextToSpeech) بصوت عربي:
 * تقرأ نص الصفحة مقطعًا مقطعًا، وعند انتهاء الصفحة تستدعي [onPageDone] لينتقل القارئ إلى الصفحة التالية ويكمل.
 * الصوت: الصوت المحدَّد في [voiceName] وإلا فالتلقائي (محلي قبل شبكي، ورجالي قبل غيره، ثم الأعلى جودة)، بغِلَظ [pitch] وسرعة [rate].
 */
class Speaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val _state = MutableStateFlow(SpeakState())
    val state: StateFlow<SpeakState> = _state
    private val _voices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    /** الأصوات العربية المثبَّتة: المحلية أولًا ثم الرجالية، ورأس القائمة هو الاختيار التلقائي */
    val voices: StateFlow<List<VoiceInfo>> = _voices
    var rate: Float = 1.0f
        set(v) { field = v; tts?.setSpeechRate(v) }
    /** غِلَظ الصوت (طبقة النطق): أقل = أعمق */
    var pitch: Float = 0.85f
        set(v) { field = v; tts?.setPitch(v) }
    /** اسم الصوت المختار؛ فارغ = تلقائي */
    var voiceName: String = ""
        set(v) { if (v != field) { field = v; if (ready) applyVoice() } }
    var onPageDone: (() -> Unit)? = null

    private var queue: List<String> = emptyList()
    private var index = 0
    private var session = 0
    private var pending: (() -> Unit)? = null         // آخر طلب وصل أثناء تهيئة المحرك (الأحدث يغلب)
    private var errors = 0                             // أخطاء نطق متتالية
    private var previewResume: Boolean? = null         // null = لا معاينة جارية؛ وإلا هل كانت القراءة جارية قبل أولى المعاينات
    private var voiceList: List<Voice> = emptyList()   // الأصوات العربية المرتّبة كما في refreshVoices

    private fun ensure(onReady: () -> Unit) {
        if (ready) { onReady(); return }
        pending = onReady
        if (tts != null) return
        var failed = false
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) { failed = true; unavailable("محرك النطق غير متوفر على هذا الجهاز"); return@TextToSpeech }
            val t = tts!!
            val r = t.setLanguage(Locale("ar"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                unavailable("لا صوت عربي مثبَّت. ثبّت بيانات اللغة العربية لمحرك النطق (إعدادات الجهاز ← اللغة ← تحويل النص إلى كلام ← Google ← تثبيت العربية).")
                return@TextToSpeech
            }
            refreshVoices(); applyVoice()
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) { if (!mine(id)) return; if (++errors < 2) next(id) else giveUp() }
                override fun onDone(id: String?) { if (mine(id)) errors = 0; next(id) }
            })
            ready = true
            val p = pending; pending = null; p?.invoke()
        }
        if (failed) shutdown()   // قد يُبلَّغ الفشل قبل عودة المُنشئ (لا محرك مثبَّت) فيُعيَّن tts بعده
    }

    /** يُسقط المحرك الفاشل ليُنشأ من جديد عند الطلب التالي (بعد تثبيت الصوت العربي مثلًا) */
    private fun unavailable(message: String) { shutdown(); _state.value = SpeakState(available = false, message = message) }

    private fun mine(id: String?) = id != null && id.startsWith("s$session-")

    /** خطآن متتاليان في النطق (كانقطاع الاتصال بصوت شبكي): توقّف كالإيقاف المؤقت يُبقي المقطع المتعثّر فيعيده [resume]، بدل تجاوز الصفحات صفحةً بعد صفحة. لا يُصفَّر العدّاد هنا عمدًا فيقف فشل المحاولة التالية عند المقطع نفسه؛ يُصفِّره [onDone] و[stop] */
    private fun giveUp() { session++; tts?.stop(); _state.value = _state.value.copy(playing = false, message = "توقفت القراءة لتعذّر النطق. اختر صوتًا آخر أو تحقّق من الاتصال ثم أعد المحاولة.") }

    private fun next(id: String?) {
        if (!mine(id)) return
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
    fun stop() {
        session++; tts?.stop(); queue = emptyList(); index = 0; pending = null; errors = 0
        if (previewResume != null) { previewResume = null; applyVoice() }   // أُغلقت الشاشة والمعاينة جارية: يعود الصوت المختار دون استئناف
        _state.value = _state.value.let { SpeakState(available = it.available, message = if (it.available) "" else it.message) }
    }
    fun shutdown() { stop(); tts?.shutdown(); tts = null; ready = false }

    // ---------- الأصوات ----------

    /** تقدير جنس الصوت من اسمه (لا واجهة رسمية له): وجود «female» ينفيه، وإلا «male» أو رمزا صوتي Google العربيان -arz و-ard */
    private fun maleLikely(name: String): Boolean { val n = name.lowercase(Locale.ROOT); return !n.contains("female") && (n.contains("male") || n.contains("-arz") || n.contains("-ard")) }

    /** الأصوات العربية المثبَّتة مرتّبة: المحلية أولًا (تعمل دون اتصال)، فالرجالية، فالأعلى جودة، فالاسم؛ ورأسها الاختيار التلقائي */
    private fun ranked(): List<Voice> =
        runCatching { tts?.voices?.filter { it.locale?.language == "ar" && it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true } }.getOrNull().orEmpty()
            .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenByDescending { maleLikely(it.name) }.thenByDescending { it.quality }.thenBy { it.name })

    /** يستعلم المحرك عن الأصوات ويخزّنها (عند التهيئة وعند فتح قائمة الأصوات، لا عند كل صفحة) */
    fun refreshVoices() {
        voiceList = ranked()
        _voices.value = voiceList.mapIndexed { i, v ->
            val quality = when { v.quality >= Voice.QUALITY_VERY_HIGH -> "جودة عالية جدًّا"; v.quality >= Voice.QUALITY_HIGH -> "جودة عالية"; else -> "جودة عادية" }
            VoiceInfo(v.name, "صوت ${ArabicText.arabicDigits(i + 1)}${if (maleLikely(v.name)) " — رجالي" else ""} ($quality${if (v.isNetworkConnectionRequired) "، عبر الشبكة" else ""})")
        }
    }

    /** يضبط على المحرك الصوت [name] (وإلا الأفضل) مع الغِلَظ والسرعة */
    private fun applyVoice(name: String = voiceName) {
        val t = tts ?: return
        (voiceList.firstOrNull { it.name == name } ?: voiceList.firstOrNull())?.let { runCatching { t.setVoice(it) } }
        t.setPitch(pitch); t.setSpeechRate(rate)
    }

    /**
     * معاينة الصوت [name] بعيّنة قصيرة بالغِلَظ والسرعة الحاليين دون تغيير الاختيار المحفوظ. تُسكت القراءة الجارية
     * وتُهمَل ردود مقطعها (بتغيير الجلسة)، ويُسجَّل عند أولى المعاينات فقط هل كانت القراءة جارية؛ يُنهيها [endPreview].
     */
    fun preview(name: String) {
        if (!ready) return   // لا تُؤجَّل المعاينة: قد تزيح طلب قراءة معلّقًا أو تنطلق بعد إغلاق القائمة
        if (previewResume == null) previewResume = _state.value.playing
        session++
        applyVoice(name); tts?.speak(PREVIEW_SAMPLE, TextToSpeech.QUEUE_FLUSH, Bundle(), "preview")
    }

    /** ينهي المعاينة: يوقفها ويعيد الصوت المختار، ويستأنف المقطع الحالي إن كانت القراءة جارية قبلها؛ دون معاينة لا يمسّ القراءة */
    fun endPreview() {
        val wasPlaying = previewResume ?: return
        previewResume = null
        tts?.stop(); applyVoice()
        if (wasPlaying && index < queue.size) speakChunk()
    }

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
