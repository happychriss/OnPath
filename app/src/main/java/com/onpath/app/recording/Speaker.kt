package com.onpath.app.recording

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.onpath.app.debug.Problems
import com.onpath.app.setup.VoiceSetup
import com.onpath.strings.Txt
import java.util.Locale

/**
 * The one place OnPath speaks (voice guidance, `docs/design/voice-guidance.md`): the phone's own text-to-speech –
 * Google's or the maker's engine, no Play Services in our code. The language is the app's text language ([Txt.language]),
 * so voice and texts always match – a new app language needs nothing here.
 *
 * Which engine and voice: [VoiceSetup] decides (pure, unit-tested) from what the engines say – the phone's default engine
 * first, Google's directly when the default has no offline voice (field report 2026-11-07, Samsung S10), an offline voice
 * before one that needs the internet. What it found is in [result] / [detail] (voice step, diagnostics).
 * Navigation-guidance audio that lets music duck; quiet during a phone call. Never throws; problems go to [Problems].
 */
class Speaker(private val context: Context) {
    enum class State { STARTING, READY, NO_VOICE, NO_LANGUAGE, NO_ENGINE }

    @Volatile var state = State.STARTING
        private set
    /** READY with a voice that needs the internet (no offline voice on the phone). */
    @Volatile var online = false
        private set
    /** Called (on the main thread) when the engines were checked: ready, or why not. */
    @Volatile var onState: ((State) -> Unit)? = null

    /** The voice in use (Diagnostics): engine, name, offline. */
    @Volatile var voiceInfo: String = "–"
        private set
    /** What the last check of the engines found; null = not checked yet. */
    @Volatile var result: VoiceSetup.Result? = null
        private set
    /** The check as one line (engines, versions, voices) – the problem log and the diagnostics export. */
    @Volatile var detail: String = "voice not checked yet"
        private set

    /** The voice can speak (or is still starting): else the tour gives its directions by vibration. */
    val canSpeak: Boolean get() = state == State.STARTING || state == State.READY

    /**
     * How long the engine takes from the call to the first sound (ms, a running mean of what it measured; owner field ride
     * 2026-11-07: 0.1–0.9 s, 1.5 s at the first sentence) – the voice says a turn that much earlier.
     */
    @Volatile var startMs: Long = com.onpath.app.navigation.VoiceGuide.SPEECH_START_MS
        private set
    private val asked = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private var tts: TextToSpeech? = null
    private var pending: String? = null
    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var focus: AudioFocusRequest? = null

    private val main = Handler(Looper.getMainLooper())
    /** Bumped by [start] / [stop]: an engine's late answer for an older check is dropped. */
    private var generation = 0
    private val probes = mutableListOf<VoiceSetup.Probe>()
    private var installed: List<VoiceSetup.Engine> = emptyList()
    private var default: VoiceSetup.Engine? = null
    /** Bound only to speak with the voice [VoiceSetup.decide] chose (an online voice in another engine). */
    private var want: VoiceSetup.Result? = null

    /** The setup test's listener and the sentence it waits for (never a silent test). */
    @Volatile private var test: ((VoiceSetup.VoiceTest) -> Unit)? = null
    @Volatile private var testId: String? = null

    /** Starts the engine (call at the tour start: the first sentence then comes without delay); after a failed check it checks again. */
    fun start() {
        if (tts != null) return
        generation++
        probes.clear(); want = null
        state = State.STARTING; online = false
        bind(null)
    }

    private fun bind(engine: String?) {
        val gen = generation
        try {
            // the answer can come inside the constructor (no engine): handled on the main thread once `tts` is set
            tts = TextToSpeech(context.applicationContext, { status -> main.post { if (gen == generation) inited(engine, status) } }, engine)
        } catch (e: Exception) {
            Problems.warn("voice", "Text-to-speech (${engine ?: "the phone's engine"}) could not be started", e)
            tts = null
            main.post { if (gen == generation) inited(engine, TextToSpeech.ERROR) }
        }
    }

    private fun inited(engine: String?, status: Int) {
        try {
            val t = tts
            if (installed.isEmpty() && t != null) readEngines(t)
            val pkg = engine ?: default?.pkg ?: "default engine"
            val eng = installed.firstOrNull { it.pkg == pkg } ?: engine(pkg)
            want?.let { w -> return speakWith(t, status, w) }
            if (status != TextToSpeech.SUCCESS || t == null) {
                probes += VoiceSetup.Probe(eng, started = false, version = version(pkg), code = status)
                return next()
            }
            val voices = voices(t)
            val lang = try { VoiceSetup.lang(t.isLanguageAvailable(locale())) } catch (e: Exception) {
                Problems.warn("voice", "Text-to-speech $pkg did not say whether it speaks ${locale().toLanguageTag()}", e); VoiceSetup.Lang.UNKNOWN
            }
            val probe = VoiceSetup.Probe(eng, true, lang, voices.map(::info), version(pkg))
            probes += probe
            if (VoiceSetup.usable(probe, language())) {
                checked(VoiceSetup.decide(language(), default, installed, probes))
                return ready(t, best(voices, offline = true), online = false)
            }
            next()
        } catch (e: Exception) {
            Problems.warn("voice", "Voice could not be set up; directions by vibration and screen only", e)
            failed(State.NO_VOICE)
        }
    }

    /** No offline voice in this engine: Google's next, or the decision. */
    private fun next() {
        VoiceSetup.nextEngine(language(), installed, probes)?.let { g ->
            Problems.trace("voice", "No offline voice in ${probes.lastOrNull()?.engine?.pkg}: trying $g")
            shutDown(); bind(g); return
        }
        val r = VoiceSetup.decide(language(), default, installed, probes)
        checked(r)
        when (r.kind) {
            VoiceSetup.Kind.ONLINE -> {
                val last = probes.lastOrNull()
                if (last?.started == true && r.engine?.pkg == last.engine.pkg && tts != null) speakWith(tts, TextToSpeech.SUCCESS, r)
                else { want = r; shutDown(); bind(r.engine?.pkg) }
            }
            VoiceSetup.Kind.READY -> { want = r; shutDown(); bind(r.engine?.pkg) } // the usable engine was an earlier one
            VoiceSetup.Kind.MISSING -> failed(State.NO_VOICE)
            VoiceSetup.Kind.NO_LANGUAGE -> failed(State.NO_LANGUAGE)
            VoiceSetup.Kind.NO_ENGINE -> failed(State.NO_ENGINE)
        }
    }

    /** Bound to the engine [r] chose: speak with its best voice (offline first, else the online one). */
    private fun speakWith(t: TextToSpeech?, status: Int, r: VoiceSetup.Result) {
        want = null
        if (status != TextToSpeech.SUCCESS || t == null) {
            Problems.warn("voice", "Text-to-speech ${r.engine?.pkg} did not start again (status $status)")
            return failed(State.NO_VOICE)
        }
        val voices = voices(t)
        val offline = best(voices, offline = true)
        ready(t, offline ?: best(voices, offline = false), online = offline == null && r.kind == VoiceSetup.Kind.ONLINE)
    }

    private fun checked(r: VoiceSetup.Result) {
        result = r
        detail = VoiceSetup.describe(language(), r, probes, installed)
        if (r.kind == VoiceSetup.Kind.READY) Problems.info("voice", detail) else Problems.warn("voice", detail)
    }

    private fun ready(t: TextToSpeech, voice: Voice?, online: Boolean) {
        t.setAudioAttributes(attrs)
        t.setSpeechRate(SPEECH_RATE)
        val engine = result?.engine?.pkg ?: "?"
        if (voice != null) {
            t.voice = voice
            voiceInfo = "$engine: ${voice.name} (${voice.locale.toLanguageTag()}, ${if (online) "needs the internet" else "offline"}, quality ${voice.quality})"
        } else {
            t.language = locale() // an engine that lists no voices but says it speaks the language
            voiceInfo = "$engine: language ${locale().toLanguageTag()} (no voice list)"
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { started(id) }
            override fun onDone(id: String?) { id?.let { asked.remove(it) }; release() }
            // cut by the next sentence (QUEUE_FLUSH) – the next one takes the focus over
            override fun onStop(id: String?, interrupted: Boolean) { id?.let { asked.remove(it) } }
            override fun onError(id: String?, errorCode: Int) { error(id, errorCode) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { error(id, null) }
        })
        this.online = online
        state = State.READY
        Problems.info("voice", "Voice ready: $voiceInfo")
        onState?.invoke(state)
        pending?.let { pending = null; say(it, test) }
    }

    private fun failed(s: State) {
        state = s; online = false
        voiceInfo = "no voice for ${locale().toLanguageTag()} (${result?.kind ?: s})"
        pending = null
        shutDown() // the next start() checks again (a voice loaded meanwhile is found)
        onState?.invoke(s)
        test?.let { t -> test = null; t(VoiceSetup.VoiceTest(VoiceSetup.Outcome.NO_VOICE)) }
    }

    private fun error(id: String?, code: Int?) {
        id?.let { asked.remove(it) }
        release()
        val net = code == TextToSpeech.ERROR_NETWORK || code == TextToSpeech.ERROR_NETWORK_TIMEOUT
        Problems.warn("voice", "A spoken sentence could not be played (${result?.engine?.pkg ?: "?"}, error ${code ?: "?"}" +
            if (net && online) "; the voice needs the internet: directions by vibration from now on)" else ")")
        if (net && online) state = State.NO_VOICE // the tour vibrates instead (canSpeak)
        if (id != null && id == testId) {
            testId = null
            test?.invoke(VoiceSetup.VoiceTest(VoiceSetup.Outcome.FAILED, code))
        }
    }

    /** Says [text], replacing anything still being said. Quiet during a call; before the engine is ready it waits. */
    fun say(text: String) = say(text, null)

    /**
     * The phone setup's test: says [text] and always answers [onResult] (on any thread) – checking, spoken, muted, or why
     * not. After a failed check it checks the engines again (a voice loaded meanwhile is found).
     */
    fun sayTest(text: String, onResult: (VoiceSetup.VoiceTest) -> Unit) {
        when (VoiceSetup.tap(canSpeakNow = state == State.READY, checking = state == State.STARTING && tts != null)) {
            VoiceSetup.Tap.SPEAK -> { test = onResult; say(text, onResult) }
            VoiceSetup.Tap.WAIT -> { test = onResult; pending = text; onResult(VoiceSetup.VoiceTest(VoiceSetup.Outcome.CHECKING)) }
            VoiceSetup.Tap.CHECK_AGAIN -> {
                stop()
                test = onResult; pending = text
                onResult(VoiceSetup.VoiceTest(VoiceSetup.Outcome.CHECKING))
                start()
            }
        }
    }

    private fun say(text: String, onTest: ((VoiceSetup.VoiceTest) -> Unit)?) {
        when (state) {
            State.STARTING -> {
                pending = text
                onTest?.invoke(VoiceSetup.VoiceTest(VoiceSetup.Outcome.CHECKING))
                return
            }
            State.NO_VOICE, State.NO_LANGUAGE, State.NO_ENGINE -> { onTest?.invoke(VoiceSetup.VoiceTest(VoiceSetup.Outcome.NO_VOICE)); return }
            State.READY -> Unit
        }
        try {
            if (inCall()) {
                Problems.trace("voice", "Not said (phone call): $text")
                onTest?.invoke(VoiceSetup.outcome(canSpeak = true, inCall = true, speakCode = null))
                return
            }
            requestFocus()
            val id = "onpath-${System.nanoTime()}"
            if (onTest != null) testId = id
            // QUEUE_FLUSH: a turn that is due cuts what is still being said (owner 2026-11-07: never swallowed)
            asked[id] = android.os.SystemClock.elapsedRealtime()
            val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) ?: TextToSpeech.ERROR
            if (r != TextToSpeech.SUCCESS) {
                asked.remove(id)
                Problems.warn("voice", "Text-to-speech refused a sentence (${result?.engine?.pkg ?: "?"}, code $r)")
                release()
            } else Problems.trace("voice", "Said: $text")
            if (onTest != null) {
                onTest(VoiceSetup.outcome(canSpeak = true, inCall = false, speakCode = r))
                if (r == TextToSpeech.SUCCESS) main.postDelayed({
                    if (testId == id) {
                        testId = null
                        Problems.warn("voice", "The test sentence never started playing (${result?.engine?.pkg ?: "?"}, ${VoiceSetup.SILENT_AFTER_MS} ms)")
                        test?.invoke(VoiceSetup.VoiceTest(VoiceSetup.Outcome.SILENT))
                    }
                }, VoiceSetup.SILENT_AFTER_MS)
            }
        } catch (e: Exception) {
            Problems.warn("voice", "A spoken sentence failed", e)
            onTest?.invoke(VoiceSetup.VoiceTest(VoiceSetup.Outcome.FAILED))
        }
    }

    /** The engine started sentence [id]: its start-up goes into [startMs] (and the trace, for the next field ride). */
    private fun started(id: String?) {
        if (id != null && id == testId) {
            testId = null
            test?.invoke(VoiceSetup.VoiceTest(if (muted()) VoiceSetup.Outcome.MUTED else VoiceSetup.Outcome.SPOKEN))
        }
        val at = id?.let { asked.remove(it) } ?: return
        val ms = (android.os.SystemClock.elapsedRealtime() - at).coerceIn(0, START_CAP_MS)
        startMs = (startMs * 2 + ms) / 3
        Problems.trace("voice", "Speech started after $ms ms (planned with $startMs ms)")
    }

    fun stop() {
        generation++
        test = null; testId = null; pending = null; want = null
        shutDown()
        state = State.STARTING; release()
    }

    private fun shutDown() {
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { Problems.warn("voice", "Text-to-speech did not shut down cleanly", e) }
        tts = null
    }

    private fun readEngines(t: TextToSpeech) {
        try {
            installed = t.engines.orEmpty().map { engine(it.name, it.label.orEmpty()) }
            default = t.defaultEngine?.let { d -> installed.firstOrNull { it.pkg == d } ?: engine(d) }
        } catch (e: Exception) {
            Problems.warn("voice", "The phone's speech engines could not be listed", e)
        }
    }

    private fun voices(t: TextToSpeech): List<Voice> = try { t.voices.orEmpty().toList() } catch (e: Exception) {
        Problems.warn("voice", "Voices of the speech engine could not be read", e); emptyList()
    }

    private fun info(v: Voice) = VoiceSetup.VoiceInfo(v.name, v.locale.language, !v.isNetworkConnectionRequired,
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty())

    /** The best installed voice for the app language, [offline] or needing the internet; null = none. */
    private fun best(voices: List<Voice>, offline: Boolean): Voice? = voices
        .filter { VoiceSetup.sameLanguage(it.locale.language, language()) && !it.isNetworkConnectionRequired == offline }
        .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
        .sortedWith(compareByDescending<Voice> { it.locale.country == Locale.getDefault().country }
            .thenByDescending { it.quality }.thenBy { it.latency })
        .firstOrNull()

    private fun version(pkg: String): String? = try {
        context.packageManager.getPackageInfo(pkg, 0).versionName
    } catch (e: Exception) { null } // expected: an engine package the app cannot see – the line then shows "v?"

    private fun inCall(): Boolean = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION

    private fun muted(): Boolean = try { audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 } catch (e: Exception) {
        Problems.warn("voice", "Media volume not read", e); false
    }

    private fun requestFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
                focus = f
                audio.requestAudioFocus(f)
            }
        } catch (e: Exception) { Problems.warn("voice", "Music could not be made quieter for a direction", e) }
    }

    private fun release() {
        try { focus?.let { audio.abandonAudioFocusRequest(it) } } catch (e: Exception) {
            Problems.warn("voice", "Audio focus not given back after a direction", e)
        }
        focus = null
    }

    companion object {
        /** Google's speech services ("Speech Services by Google"): tried directly when the phone's engine has no voice. */
        const val GOOGLE_TTS = "com.google.android.tts"
        const val GOOGLE_STORE = "market://details?id=$GOOGLE_TTS"
        const val GOOGLE_WEB = "https://play.google.com/store/apps/details?id=$GOOGLE_TTS"

        fun engine(pkg: String, label: String = "") = VoiceSetup.Engine(pkg, label, google = pkg == GOOGLE_TTS)

        /** A little slower than the engine's default: older users (owner). */
        const val SPEECH_RATE = 0.9f
        /** A start-up measured longer than this (a call, the engine woken) counts as this. */
        const val START_CAP_MS = 2_500L

        /** What the phone check stores for [s] ([online]: READY with a voice that needs the internet). */
        fun status(s: State, online: Boolean = false): com.onpath.app.setup.VoiceStatus = when (s) {
            State.READY -> if (online) com.onpath.app.setup.VoiceStatus.ONLINE else com.onpath.app.setup.VoiceStatus.READY
            State.NO_VOICE -> com.onpath.app.setup.VoiceStatus.MISSING
            State.NO_LANGUAGE -> com.onpath.app.setup.VoiceStatus.NO_LANGUAGE
            State.NO_ENGINE -> com.onpath.app.setup.VoiceStatus.NO_ENGINE
            State.STARTING -> com.onpath.app.setup.VoiceStatus.UNKNOWN
        }

        /** The app's text language as a locale (voice = texts). */
        fun locale(): Locale = Locale.forLanguageTag(Txt.language())

        fun language(): String = locale().language

        /** Stores what [s] found for the phone check (status, the engine to name, the line for diagnostics). */
        fun remember(prefs: com.onpath.app.data.Prefs, s: Speaker) {
            prefs.voiceStatus = status(s.state, s.online)
            prefs.voiceFix = s.result?.let(VoiceSetup::fix)
            prefs.voiceDetail = "${s.detail}; in use: ${s.voiceInfo}"
        }
    }
}
