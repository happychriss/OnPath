package com.onpath.app.setup

import java.util.Locale

/**
 * Which of the phone's speech engines speaks the app's language, and what the voice step tells the user when none can
 * (pure; recording/Speaker reads the engines, ui/setup draws [advice] and [VoiceTest]).
 *
 * Field report 2026-11-07 (Play internal test, Samsung Galaxy S10 SM-G973F, Android 12): the phone's engine was
 * Samsung's (`com.samsung.SMT`) without an offline German voice; "Load voice" led into Samsung's language menu and back,
 * and every later tap on Test stayed silent. So:
 * - every engine counts, not only Google's; the phone's default engine wins when it has an offline voice (the user's
 *   choice);
 * - when it has none and Google's engine is installed, OnPath tries Google's directly
 *   (`TextToSpeech(context, listener, Speaker.GOOGLE_TTS)`): no system setting to change, and its voice download works the same on
 *   every phone; a voice loaded into the default engine later wins again;
 * - offline first, but silence is worse: with no offline voice anywhere, an installed voice that needs the internet is
 *   used ([Kind.ONLINE]) – the step says so and keeps offering the offline download, and the tour vibrates when that
 *   voice fails without a network;
 * - otherwise the step names the phone's engine and says what to do (load the voice, or install Google's).
 */
object VoiceSetup {

    /** `TextToSpeech.isLanguageAvailable` in words. */
    enum class Lang { AVAILABLE, MISSING_DATA, NOT_SUPPORTED, UNKNOWN }

    /** LANG_AVAILABLE 0 / LANG_COUNTRY_AVAILABLE 1 / LANG_COUNTRY_VAR_AVAILABLE 2, LANG_MISSING_DATA -1, LANG_NOT_SUPPORTED -2. */
    fun lang(code: Int?): Lang = when (code) {
        0, 1, 2 -> Lang.AVAILABLE
        -1 -> Lang.MISSING_DATA
        -2 -> Lang.NOT_SUPPORTED
        else -> Lang.UNKNOWN
    }

    /** One voice of an engine: its language (`Voice.locale.language`, 2 or 3 letters), offline, installed. */
    data class VoiceInfo(val name: String, val language: String, val offline: Boolean, val installed: Boolean)

    /**
     * An installed engine: package, the name the phone shows ("Samsung TTS", "Speech Services by Google") and whether it
     * is Google's speech services (the package name lives in the app layer: recording/Speaker.GOOGLE_TTS).
     */
    data class Engine(val pkg: String, val label: String = "", val google: Boolean = false) {
        val shown: String get() = label.ifBlank { pkg }
    }

    /** What one engine said; [started] false = it did not start (init status [code]). */
    data class Probe(
        val engine: Engine,
        val started: Boolean,
        val lang: Lang = Lang.UNKNOWN,
        val voices: List<VoiceInfo> = emptyList(),
        val version: String? = null,
        val code: Int? = null,
    )

    enum class Kind { READY, ONLINE, MISSING, NO_LANGUAGE, NO_ENGINE }

    /**
     * [engine]: READY / ONLINE = the one that speaks; MISSING = the one to load the voice into; NO_LANGUAGE = the phone's
     * engine that lacks the language. [default] = the phone's preferred engine.
     */
    data class Result(val kind: Kind, val engine: Engine?, val default: Engine?, val googleInstalled: Boolean) {
        val status: VoiceStatus get() = when (kind) {
            Kind.READY -> VoiceStatus.READY
            Kind.ONLINE -> VoiceStatus.ONLINE
            Kind.MISSING -> VoiceStatus.MISSING
            Kind.NO_LANGUAGE -> VoiceStatus.NO_LANGUAGE
            Kind.NO_ENGINE -> VoiceStatus.NO_ENGINE
        }
    }

    /** "de" = "deu" (some engines name their voices with ISO 639-2 codes). */
    fun sameLanguage(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        fun iso3(x: String) = try { Locale(x).isO3Language } catch (e: java.util.MissingResourceException) { x } // expected: an unknown code compares as written
        return iso3(a).equals(iso3(b), ignoreCase = true)
    }

    private fun same(p: Probe, language: String) = if (!p.started) emptyList() else p.voices.filter { sameLanguage(it.language, language) }

    /** Installed voices for [language] that need no network. */
    fun offlineVoices(p: Probe, language: String): List<VoiceInfo> = same(p, language).filter { it.offline && it.installed }

    /** Installed voices for [language] that need the internet. */
    fun onlineVoices(p: Probe, language: String): List<VoiceInfo> = same(p, language).filter { !it.offline && it.installed }

    /** An offline voice to speak with – or, for an engine that lists no voices at all, the language says it is there. */
    fun usable(p: Probe, language: String): Boolean =
        offlineVoices(p, language).isNotEmpty() || (p.started && p.voices.isEmpty() && p.lang == Lang.AVAILABLE)

    /** The engine knows the language but has no offline voice for it yet: loading one there helps. */
    fun canDownload(p: Probe, language: String): Boolean = p.started &&
        (p.lang == Lang.MISSING_DATA || p.lang == Lang.AVAILABLE || same(p, language).isNotEmpty())

    /** The engine to try after [probes]: Google's, when it is installed, not tried yet and no offline voice was found. */
    fun nextEngine(language: String, installed: List<Engine>, probes: List<Probe>): String? =
        if (probes.any { usable(it, language) }) null
        else installed.firstOrNull { it.google && probes.none { p -> p.engine.pkg == it.pkg } }?.pkg

    fun decide(language: String, default: Engine?, installed: List<Engine>, probes: List<Probe>): Result {
        val google = installed.any { it.google } || probes.any { it.engine.google && it.started }
        val started = probes.filter { it.started }
        if (started.isEmpty()) return Result(Kind.NO_ENGINE, null, default, google)
        fun pick(list: List<Probe>) = list.firstOrNull { it.engine.pkg == default?.pkg } ?: list.firstOrNull { it.engine.google } ?: list.first()
        fun result(kind: Kind, list: List<Probe>) = Result(kind, pick(list).engine, default, google)
        started.filter { usable(it, language) }.takeIf { it.isNotEmpty() }?.let { return result(Kind.READY, it) }
        started.filter { onlineVoices(it, language).isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { return result(Kind.ONLINE, it) }
        // loading the voice: into Google's engine when it is there (its download works the same on every phone, and
        // OnPath then uses it directly), else into the phone's own
        started.filter { canDownload(it, language) }.takeIf { it.isNotEmpty() }?.let { dl ->
            return Result(Kind.MISSING, (dl.firstOrNull { it.engine.google } ?: pick(dl)).engine, default, google)
        }
        return result(Kind.NO_LANGUAGE, started)
    }

    /** What the voice step shows and offers (besides Test and Later). */
    enum class Advice {
        /** Nothing more than the step's own text. */
        NONE,
        /** "Load voice" into the phone's (Google) engine – the step's old text. */
        DOWNLOAD,
        /** The phone's engine has no voice; OnPath uses Google's – load the voice there. */
        DOWNLOAD_IN_GOOGLE,
        /** "Load voice" in the phone's own engine; if that does not work, install Google's. */
        DOWNLOAD_OR_GOOGLE,
        /** The phone's engine does not speak the language and Google's is missing: install it. */
        INSTALL_GOOGLE,
        /** No engine on the phone speaks the language (Google's neither): the speech settings. */
        NO_LANGUAGE_ANYWHERE,
        /** Only a voice that needs the internet: it speaks, but load an offline one for tours without a network. */
        ONLINE_ONLY,
    }

    /** What the voice step needs to know about the engines (stored with the status, [encode]). */
    data class Fix(val enginePkg: String, val engine: String, val engineGoogle: Boolean, val default: String?, val defaultGoogle: Boolean,
                   val googleInstalled: Boolean)

    fun fix(r: Result): Fix? =
        r.engine?.let { Fix(it.pkg, it.shown, it.google, r.default?.shown, r.default?.google ?: false, r.googleInstalled) }

    fun advice(status: VoiceStatus, fix: Fix?): Advice = when (status) {
        VoiceStatus.MISSING -> when {
            fix == null || fix.engineGoogle && (fix.default == null || fix.defaultGoogle) -> Advice.DOWNLOAD
            fix.engineGoogle -> Advice.DOWNLOAD_IN_GOOGLE
            else -> Advice.DOWNLOAD_OR_GOOGLE
        }
        VoiceStatus.NO_LANGUAGE -> if (fix?.googleInstalled == true) Advice.NO_LANGUAGE_ANYWHERE else Advice.INSTALL_GOOGLE
        VoiceStatus.ONLINE -> Advice.ONLINE_ONLY
        else -> Advice.NONE
    }

    /** The voice step offers "Speech Services by Google" from the Play Store: not installed, and it would help. */
    fun offerGoogle(a: Advice, fix: Fix?): Boolean = fix?.googleInstalled != true &&
        (a == Advice.DOWNLOAD_OR_GOOGLE || a == Advice.INSTALL_GOOGLE || a == Advice.ONLINE_ONLY && fix?.engineGoogle != true)

    private const val SEP = '\u001F'

    fun encode(f: Fix?): String =
        f?.let { listOf(it.enginePkg, it.engine, it.engineGoogle, it.default ?: "", it.defaultGoogle, it.googleInstalled).joinToString("$SEP") } ?: ""

    fun decode(s: String?): Fix? {
        val p = s?.takeIf { it.isNotEmpty() }?.split(SEP) ?: return null
        if (p.size != 6) return null
        return Fix(p[0], p[1], p[2].toBoolean(), p[3].ifEmpty { null }, p[4].toBoolean(), p[5].toBoolean())
    }

    /** One line for the problem log and the diagnostics export (testers on other phones): engines, versions, voices. */
    fun describe(language: String, r: Result, probes: List<Probe>, installed: List<Engine>): String = buildString {
        append("Voice for $language: ${r.kind}")
        r.engine?.let { append(" (${it.pkg})") }
        append(", default ${r.default?.pkg ?: "?"}, engines ${installed.joinToString("/") { it.pkg }.ifEmpty { "none listed" }}")
        for (p in probes) {
            append("; ${p.engine.pkg} \"${p.engine.label}\" ${p.version ?: "v?"}")
            if (!p.started) { append(" did not start (status ${p.code ?: "?"})"); continue }
            val s = same(p, language)
            append(": language ${p.lang}, $language voices ${s.size} (offline ${s.count { it.offline }}, installed ${s.count { it.installed }}, ")
            append("offline+installed ${offlineVoices(p, language).size}) of ${p.voices.size}")
        }
    }

    /** What the last tap on Test did – the voice step always shows it (never a silent test). */
    enum class Outcome {
        /** The engines are being checked / the sentence is on its way. */
        CHECKING,
        /** The sentence started playing. */
        SPOKEN,
        /** Spoken, but the media volume is at zero. */
        MUTED,
        /** No voice to speak with (the step's text says what to do). */
        NO_VOICE,
        /** A phone call is on: the voice stays quiet. */
        IN_CALL,
        /** The engine refused the sentence or reported an error ([VoiceTest.code]). */
        FAILED,
        /** The engine took the sentence but never started speaking. */
        SILENT,
    }

    data class VoiceTest(val outcome: Outcome, val code: Int? = null) {
        /** The test sentence played (the step then asks "Did you hear it?"). */
        val played: Boolean get() = outcome == Outcome.SPOKEN || outcome == Outcome.MUTED
    }

    /** The test's outcome: [canSpeak] = READY / ONLINE; [speakCode] = `TextToSpeech.speak`'s answer (0 = SUCCESS). */
    fun outcome(canSpeak: Boolean, inCall: Boolean, speakCode: Int?): VoiceTest = when {
        !canSpeak -> VoiceTest(Outcome.NO_VOICE)
        inCall -> VoiceTest(Outcome.IN_CALL)
        speakCode != 0 -> VoiceTest(Outcome.FAILED, speakCode)
        else -> VoiceTest(Outcome.CHECKING)
    }

    /** What a tap on Test does with the speaker. */
    enum class Tap { SPEAK, WAIT, CHECK_AGAIN }

    /**
     * Field bug 2026-11-07 (Samsung S10): after a check that found no voice, every later tap went to the same speaker,
     * which returned without a word and without looking again – the user loaded a voice and heard nothing, saw nothing.
     * Now: a voice → speak; a check running → wait for it; otherwise (no voice found, not started) → check the engines
     * again. Every branch answers on the screen ([Outcome.CHECKING] first, then the result).
     */
    fun tap(canSpeakNow: Boolean, checking: Boolean): Tap = when {
        canSpeakNow -> Tap.SPEAK
        checking -> Tap.WAIT
        else -> Tap.CHECK_AGAIN
    }

    /** No `onStart` from the engine this long after `speak` returned SUCCESS → [Outcome.SILENT]. */
    const val SILENT_AFTER_MS = 6_000L
}
