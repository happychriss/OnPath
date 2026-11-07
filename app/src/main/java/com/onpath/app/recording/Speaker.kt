package com.onpath.app.recording

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.onpath.app.debug.Problems
import com.onpath.strings.Txt
import java.util.Locale

/**
 * The one place OnPath speaks (voice guidance, `docs/design/voice-guidance.md`): the phone's own text-to-speech –
 * Google's or the maker's engine, no Play Services in our code. Only voices that work offline; the language is the
 * app's text language ([Txt.language]), so voice and texts always match – a new app language needs nothing here.
 * Navigation-guidance audio that lets music duck; quiet during a phone call. Never throws; problems go to [Problems].
 */
class Speaker(private val context: Context) {
    enum class State { STARTING, READY, NO_VOICE, NO_ENGINE }

    @Volatile var state = State.STARTING
        private set
    /** Called (on the engine's thread) when the engine is ready or found no voice / no engine. */
    @Volatile var onState: ((State) -> Unit)? = null

    /** The voice in use (Diagnostics): name, offline. */
    @Volatile var voiceInfo: String = "–"
        private set

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

    /** Starts the engine (call at the tour start: the first sentence then comes without delay). */
    fun start() {
        if (tts != null) return
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    state = State.NO_ENGINE
                    Problems.warn("voice", "No text-to-speech engine on this phone; directions by vibration and screen only")
                    onState?.invoke(state)
                    return@TextToSpeech
                }
                setUp()
            }
        } catch (e: Exception) {
            state = State.NO_ENGINE
            Problems.warn("voice", "Text-to-speech could not be started; directions by vibration and screen only", e)
            onState?.invoke(state)
        }
    }

    private fun setUp() {
        val t = tts ?: return
        try {
            t.setAudioAttributes(attrs)
            t.setSpeechRate(SPEECH_RATE)
            val voice = bestOfflineVoice(t, locale())
            if (voice == null) {
                state = State.NO_VOICE
                voiceInfo = "no offline voice for ${locale().toLanguageTag()}"
                Problems.warn("voice", "No offline voice for ${locale().toLanguageTag()} (phone setup → Voice shows how to load one)")
                pending = null
                onState?.invoke(state)
                return
            }
            t.voice = voice
            voiceInfo = "${voice.name} (${voice.locale.toLanguageTag()}, offline, quality ${voice.quality})"
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { started(id) }
                override fun onDone(id: String?) { id?.let { asked.remove(it) }; release() }
                // cut by the next sentence (QUEUE_FLUSH) – the next one takes the focus over
                override fun onStop(id: String?, interrupted: Boolean) { id?.let { asked.remove(it) } }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) {
                    id?.let { asked.remove(it) }
                    Problems.warn("voice", "A spoken direction could not be played"); release()
                }
            })
            state = State.READY
            Problems.info("voice", "Voice ready: $voiceInfo")
            onState?.invoke(state)
            pending?.let { pending = null; say(it) }
        } catch (e: Exception) {
            state = State.NO_VOICE
            Problems.warn("voice", "Voice could not be set up; directions by vibration and screen only", e)
            onState?.invoke(state)
        }
    }

    /** Says [text], replacing anything still being said. Quiet during a call; before the engine is ready it waits. */
    fun say(text: String) {
        when (state) {
            State.STARTING -> { pending = text; return }
            State.NO_VOICE, State.NO_ENGINE -> return
            State.READY -> Unit
        }
        try {
            if (inCall()) { Problems.trace("voice", "Not said (phone call): $text"); return }
            requestFocus()
            val id = "onpath-${System.nanoTime()}"
            // QUEUE_FLUSH: a turn that is due cuts what is still being said (owner 2026-11-07: never swallowed)
            asked[id] = android.os.SystemClock.elapsedRealtime()
            val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (r != TextToSpeech.SUCCESS) { asked.remove(id); Problems.warn("voice", "Text-to-speech refused a direction (code $r)"); release() }
            else Problems.trace("voice", "Said: $text")
        } catch (e: Exception) {
            Problems.warn("voice", "A spoken direction failed", e)
        }
    }

    /** The engine started sentence [id]: its start-up goes into [startMs] (and the trace, for the next field ride). */
    private fun started(id: String?) {
        val at = id?.let { asked.remove(it) } ?: return
        val ms = (android.os.SystemClock.elapsedRealtime() - at).coerceIn(0, START_CAP_MS)
        startMs = (startMs * 2 + ms) / 3
        Problems.trace("voice", "Speech started after $ms ms (planned with $startMs ms)")
    }

    fun stop() {
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { Problems.warn("voice", "Text-to-speech did not shut down cleanly", e) }
        tts = null; state = State.STARTING; release()
    }

    private fun inCall(): Boolean = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION

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
        /** A little slower than the engine's default: older users (owner). */
        const val SPEECH_RATE = 0.9f
        /** A start-up measured longer than this (a call, the engine woken) counts as this. */
        const val START_CAP_MS = 2_500L

        /** What the phone check stores for [s]. */
        fun status(s: State): com.onpath.app.setup.VoiceStatus = when (s) {
            State.READY -> com.onpath.app.setup.VoiceStatus.READY
            State.NO_VOICE -> com.onpath.app.setup.VoiceStatus.MISSING
            State.NO_ENGINE -> com.onpath.app.setup.VoiceStatus.NO_ENGINE
            State.STARTING -> com.onpath.app.setup.VoiceStatus.UNKNOWN
        }

        /** The app's text language as a locale (voice = texts). */
        fun locale(): Locale = Locale.forLanguageTag(Txt.language())

        /** The best voice for [locale] that needs no network and is installed; null = none. */
        fun bestOfflineVoice(t: TextToSpeech, locale: Locale): Voice? = try {
            t.voices.orEmpty()
                .filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired }
                .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                .sortedWith(compareByDescending<Voice> { it.locale.country == Locale.getDefault().country }
                    .thenByDescending { it.quality }.thenBy { it.latency })
                .firstOrNull()
        } catch (e: Exception) {
            Problems.warn("voice", "Voices of the speech engine could not be read", e); null
        }
    }
}
