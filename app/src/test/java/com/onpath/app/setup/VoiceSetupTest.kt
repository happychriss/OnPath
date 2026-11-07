package com.onpath.app.setup

import com.onpath.app.setup.VoiceSetup.Advice
import com.onpath.app.setup.VoiceSetup.Engine
import com.onpath.app.setup.VoiceSetup.Kind
import com.onpath.app.setup.VoiceSetup.Lang
import com.onpath.app.setup.VoiceSetup.Outcome
import com.onpath.app.setup.VoiceSetup.Probe
import com.onpath.app.setup.VoiceSetup.VoiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which engine speaks, and what the voice step says (field report 2026-11-07: Samsung Galaxy S10, Android 12, the
 * phone's engine `com.samsung.SMT` without an offline German voice – "No offline voice for de", MISSING, then a silent test).
 */
class VoiceSetupTest {
    private val samsung = Engine("com.samsung.SMT", "Samsung TTS")
    private val google = Engine("com.google.android.tts", "Speech Services by Google", google = true)
    private val de = "de"

    private fun offline(lang: String = de, name: String = "$lang-x-local") = VoiceInfo(name, lang, offline = true, installed = true)
    private fun notLoaded(lang: String = de) = VoiceInfo("$lang-x-download", lang, offline = true, installed = false)
    private fun network(lang: String = de) = VoiceInfo("$lang-x-network", lang, offline = false, installed = true)
    private fun probe(e: Engine, lang: Lang, vararg v: VoiceInfo) = Probe(e, true, lang, v.toList(), "1.0")

    /** The owner's S10: Samsung's engine only, German known but not loaded → load it there, or install Google's. */
    @Test fun samsungWithoutGermanVoiceAndNoGoogle_loadThereOrInstallGoogle() {
        val p = listOf(probe(samsung, Lang.MISSING_DATA, notLoaded(), offline("en"), offline("ko")))
        assertNull("no Google engine to try", VoiceSetup.nextEngine(de, listOf(samsung), p))
        val r = VoiceSetup.decide(de, samsung, listOf(samsung), p)
        assertEquals(Kind.MISSING, r.kind)
        assertEquals(samsung, r.engine)
        assertEquals(VoiceStatus.MISSING, r.status)
        val fix = VoiceSetup.fix(r)
        assertEquals(Advice.DOWNLOAD_OR_GOOGLE, VoiceSetup.advice(r.status, fix))
        assertTrue(VoiceSetup.offerGoogle(Advice.DOWNLOAD_OR_GOOGLE, fix))
    }

    /** Samsung's engine without German, Google's installed with an offline German voice: OnPath speaks with Google's. */
    @Test fun samsungDefaultGoogleInstalledWithVoice_usesGoogleDirectly() {
        val installed = listOf(samsung, google)
        val first = listOf(probe(samsung, Lang.MISSING_DATA, notLoaded()))
        assertEquals("com.google.android.tts", VoiceSetup.nextEngine(de, installed, first))
        val both = first + probe(google, Lang.AVAILABLE, offline(), network())
        assertNull("nothing more to try", VoiceSetup.nextEngine(de, installed, both))
        val r = VoiceSetup.decide(de, samsung, installed, both)
        assertEquals(Kind.READY, r.kind)
        assertEquals(google, r.engine)
        assertEquals(Advice.NONE, VoiceSetup.advice(r.status, VoiceSetup.fix(r)))
    }

    /** Both engines without the voice: load it into Google's (its download works the same everywhere; OnPath then uses it). */
    @Test fun neitherHasTheVoice_loadIntoGoogle() {
        val installed = listOf(samsung, google)
        val p = listOf(probe(samsung, Lang.MISSING_DATA, notLoaded()), probe(google, Lang.MISSING_DATA, notLoaded()))
        val r = VoiceSetup.decide(de, samsung, installed, p)
        assertEquals(Kind.MISSING, r.kind)
        assertEquals(google, r.engine)
        assertEquals(Advice.DOWNLOAD_IN_GOOGLE, VoiceSetup.advice(r.status, VoiceSetup.fix(r)))
        assertFalse("installed already", VoiceSetup.offerGoogle(Advice.DOWNLOAD_IN_GOOGLE, VoiceSetup.fix(r)))
    }

    /** The phone's engine does not speak German at all and Google's is missing: suggest installing it. */
    @Test fun engineWithoutTheLanguage_suggestGoogle() {
        val p = listOf(probe(samsung, Lang.NOT_SUPPORTED, offline("en"), offline("ko")))
        val r = VoiceSetup.decide(de, samsung, listOf(samsung), p)
        assertEquals(Kind.NO_LANGUAGE, r.kind)
        assertEquals(VoiceStatus.NO_LANGUAGE, r.status)
        val fix = VoiceSetup.fix(r)
        assertEquals(Advice.INSTALL_GOOGLE, VoiceSetup.advice(r.status, fix))
        assertTrue(VoiceSetup.offerGoogle(Advice.INSTALL_GOOGLE, fix))
        // Google's there and does not speak it either: the speech settings, no store button
        val withGoogle = VoiceSetup.decide(de, samsung, listOf(samsung, google), p + probe(google, Lang.NOT_SUPPORTED, offline("en")))
        assertEquals(Kind.NO_LANGUAGE, withGoogle.kind)
        assertEquals(Advice.NO_LANGUAGE_ANYWHERE, VoiceSetup.advice(withGoogle.status, VoiceSetup.fix(withGoogle)))
        assertFalse(VoiceSetup.offerGoogle(Advice.NO_LANGUAGE_ANYWHERE, VoiceSetup.fix(withGoogle)))
    }

    /** No engine at all (or none starts): NO_ENGINE – the step only says so (vibration and screen). */
    @Test fun noEngine() {
        assertEquals(Kind.NO_ENGINE, VoiceSetup.decide(de, null, emptyList(), emptyList()).kind)
        val failed = listOf(Probe(samsung, started = false, code = -1))
        assertEquals(Kind.NO_ENGINE, VoiceSetup.decide(de, samsung, listOf(samsung), failed).kind)
        assertEquals(VoiceStatus.NO_ENGINE, VoiceSetup.decide(de, samsung, listOf(samsung), failed).status)
    }

    /** A Pixel: Google's engine is the default and has the voice – ready, nothing else tried. */
    @Test fun googleDefaultWithVoice_ready() {
        val p = listOf(probe(google, Lang.AVAILABLE, offline(), network()))
        assertNull(VoiceSetup.nextEngine(de, listOf(google), p))
        val r = VoiceSetup.decide(de, google, listOf(google), p)
        assertEquals(Kind.READY, r.kind)
        assertEquals(google, r.engine)
    }

    /** Google's engine is the default but the voice is not loaded: the step's old text (load voice). */
    @Test fun googleDefaultMissingVoice_download() {
        val p = listOf(probe(google, Lang.MISSING_DATA, notLoaded()))
        val r = VoiceSetup.decide(de, google, listOf(google), p)
        assertEquals(Kind.MISSING, r.kind)
        assertEquals(Advice.DOWNLOAD, VoiceSetup.advice(r.status, VoiceSetup.fix(r)))
        assertEquals("an old stored status without engine info", Advice.DOWNLOAD, VoiceSetup.advice(VoiceStatus.MISSING, null))
    }

    /** The phone's default with a voice wins over Google's (the user's choice). */
    @Test fun defaultEngineWithVoiceWins() {
        val p = listOf(probe(samsung, Lang.AVAILABLE, offline()), probe(google, Lang.AVAILABLE, offline()))
        assertEquals(samsung, VoiceSetup.decide(de, samsung, listOf(samsung, google), p).engine)
        assertNull("the default has a voice: Google's is not even tried", VoiceSetup.nextEngine(de, listOf(samsung, google), p.take(1)))
    }

    /** Offline first, but silence is worse: only a voice that needs the internet → speak with it, keep asking for offline. */
    @Test fun onlyANetworkVoice_speaksOnline() {
        val p = listOf(probe(samsung, Lang.AVAILABLE, network()))
        val r = VoiceSetup.decide(de, samsung, listOf(samsung), p)
        assertEquals(Kind.ONLINE, r.kind)
        assertEquals(VoiceStatus.ONLINE, r.status)
        assertEquals(Advice.ONLINE_ONLY, VoiceSetup.advice(r.status, VoiceSetup.fix(r)))
        assertTrue(VoiceSetup.offerGoogle(Advice.ONLINE_ONLY, VoiceSetup.fix(r)))
        // an offline voice anywhere wins over it
        val withGoogle = p + probe(google, Lang.AVAILABLE, offline())
        assertEquals(Kind.READY, VoiceSetup.decide(de, samsung, listOf(samsung, google), withGoogle).kind)
        // the step counts it as not done (recommended: before a tour it asks again, "Later" for a week)
        val s = PhoneState(sdk = 31, voice = VoiceStatus.ONLINE, voiceHeard = Felt.YES)
        assertFalse(PhoneCheck.isOk(CheckId.VOICE, s))
        assertTrue(PhoneCheck.isOk(CheckId.VOICE, s.copy(voice = VoiceStatus.READY)))
        assertFalse(PhoneCheck.isOk(CheckId.VOICE, s.copy(voice = VoiceStatus.NO_LANGUAGE)))
    }

    /** Some engines name voices with ISO 639-2 codes ("deu"): the same language. */
    @Test fun iso3VoiceLanguageCounts() {
        assertTrue(VoiceSetup.sameLanguage("deu", "de"))
        assertTrue(VoiceSetup.sameLanguage("de", "DE"))
        assertFalse(VoiceSetup.sameLanguage("en", "de"))
        assertFalse(VoiceSetup.sameLanguage("xx", "de"))
        assertTrue(VoiceSetup.usable(probe(samsung, Lang.AVAILABLE, offline("deu")), de))
    }

    /** An engine that lists no voices but says it speaks the language is usable (setLanguage). */
    @Test fun engineWithoutVoiceList() {
        assertTrue(VoiceSetup.usable(probe(samsung, Lang.AVAILABLE), de))
        assertFalse(VoiceSetup.usable(probe(samsung, Lang.MISSING_DATA), de))
    }

    @Test fun fixIsStoredAndReadBack() {
        val f = VoiceSetup.Fix("com.samsung.SMT", "Samsung TTS", engineGoogle = false, default = "Samsung TTS", defaultGoogle = false, googleInstalled = false)
        assertEquals(f, VoiceSetup.decode(VoiceSetup.encode(f)))
        assertNull(VoiceSetup.decode(VoiceSetup.encode(null)))
        assertNull(VoiceSetup.decode("broken"))
    }

    /**
     * The field bug itself: the S10's events show one engine bind and "speech output MISSING" at the first tap, then
     * nothing at all for every later tap (the old speaker stayed "no voice" and returned silently). A tap after "no voice"
     * now checks the engines again – a voice loaded meanwhile is found – and shows "Checking …" first.
     */
    @Test fun aTapAfterNoVoiceChecksAgain_neverSilent() {
        assertEquals(VoiceSetup.Tap.CHECK_AGAIN, VoiceSetup.tap(canSpeakNow = false, checking = false))
        assertEquals(VoiceSetup.Tap.WAIT, VoiceSetup.tap(canSpeakNow = false, checking = true))
        assertEquals(VoiceSetup.Tap.SPEAK, VoiceSetup.tap(canSpeakNow = true, checking = false))
        // the second check, after the voice was loaded into Samsung's engine: ready
        val after = listOf(probe(samsung, Lang.AVAILABLE, offline()))
        assertEquals(Kind.READY, VoiceSetup.decide(de, samsung, listOf(samsung), after).kind)
    }

    /** The test button always answers: no voice, a call, a refusal, or "on its way" (then spoken / muted / silent). */
    @Test fun testOutcome() {
        assertEquals(Outcome.NO_VOICE, VoiceSetup.outcome(canSpeak = false, inCall = false, speakCode = null).outcome)
        assertEquals(Outcome.IN_CALL, VoiceSetup.outcome(canSpeak = true, inCall = true, speakCode = null).outcome)
        val refused = VoiceSetup.outcome(canSpeak = true, inCall = false, speakCode = -1)
        assertEquals(Outcome.FAILED, refused.outcome)
        assertEquals(-1, refused.code)
        assertEquals(Outcome.CHECKING, VoiceSetup.outcome(canSpeak = true, inCall = false, speakCode = 0).outcome)
        assertTrue(VoiceSetup.VoiceTest(Outcome.SPOKEN).played)
        assertTrue(VoiceSetup.VoiceTest(Outcome.MUTED).played)
        assertFalse(VoiceSetup.VoiceTest(Outcome.SILENT).played)
    }

    /** The diagnostics line names the engines, their versions and the voices (a tester's report shows it). */
    @Test fun describeNamesEnginesAndVoices() {
        val p = listOf(probe(samsung, Lang.MISSING_DATA, notLoaded(), offline("en")), Probe(google, started = false, code = -1))
        val r = VoiceSetup.decide(de, samsung, listOf(samsung, google), p)
        val line = VoiceSetup.describe(de, r, p, listOf(samsung, google))
        assertTrue(line, line.startsWith("Voice for de: MISSING (com.samsung.SMT), default com.samsung.SMT"))
        assertTrue(line, "\"Samsung TTS\" 1.0: language MISSING_DATA, de voices 1 (offline 1, installed 0, offline+installed 0) of 2" in line)
        assertTrue(line, "com.google.android.tts \"Speech Services by Google\" v? did not start (status -1)" in line)
    }
}
