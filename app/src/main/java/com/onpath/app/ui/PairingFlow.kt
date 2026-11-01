package com.onpath.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.Prefs
import com.onpath.app.debug.Problems
import com.onpath.strings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pairing with a browser (A-007 / A-017, docs/design/web-sync.md) – the pairing half of the browser sync, out of
 * AppViewModel (review Arch 3, owner 2026-11-01), the same code: ☰ → Pair, the camera, a scanned link's question, Pair /
 * Cancel / Unpair and where the app lands afterwards. The sync itself, its passes and the routing of plans from the
 * browser stay in the ViewModel. [screens] = what pairing reads and changes of the app's screens.
 */
class PairingFlow(
    private val sync: com.onpath.app.plansync.PlanSync,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val screens: Screens,
) {
    /** The app's screens as pairing sees them (the ViewModel's own properties). */
    interface Screens {
        var showSettings: Boolean
        var settingsPage: SettingsPage?
        var routesOpen: Boolean
        var syncNote: SyncNote?
        fun openSettings(page: SettingsPage? = null)
        fun say(text: String, kind: NoticeKind, place: NoticePlace)
    }

    /** A pairing link was opened: the secret, until Pair / Cancel in Settings → Browser sync; null = no question. */
    var pairAsk by mutableStateOf<ByteArray?>(null)
        private set

    fun syncNoteOk() { screens.syncNote = null }

    /** ☰ → Pair with browser, the first time: the step line + Open camera as a question box on the Plan map ([PairEntry]). */
    var pairHint by mutableStateOf(false)
        private set

    /** ☰ → Pair with browser: camera at once (paired / a problem → Settings → Browser sync; first time the step line). */
    fun startPairing(start: (String) -> Unit) {
        val step = PairEntry.step(sync.state.value, prefs.pairHintSeen)
        Problems.info("sync", "Pair with browser from the menu: $step")
        when (step) {
            PairEntry.Step.SETTINGS -> screens.openSettings(SettingsPage.SYNC)
            PairEntry.Step.HINT -> pairHint = true
            PairEntry.Step.CAMERA -> openPairCamera(start, fromSettings = false)
        }
    }

    fun pairHintCamera(start: (String) -> Unit) { pairHint = false; openPairCamera(start, fromSettings = false) }
    fun pairHintCancel() { pairHint = false }

    /**
     * The camera for the pairing code. No camera app: from the map → Settings → Browser sync (its steps) with the line;
     * on that page → the line there.
     */
    fun openPairCamera(start: (String) -> Unit, fromSettings: Boolean) {
        val opened = PairEntry.openCamera(start) { e ->
            Problems.warn("sync", "No camera app to scan the pairing code", e)
            if (!fromSettings) screens.openSettings(SettingsPage.SYNC)
            screens.say(Txt.s(Res.string.sync_no_camera), NoticeKind.ERROR, NoticePlace.SETTINGS)
        }
        if (opened) prefs.pairHintSeen = true
    }

    /** A pairing link (camera / QR code, a tapped link): Settings → Browser sync shows the check digits and asks. */
    fun receivePairing(secret: ByteArray, via: String) {
        screens.openSettings(SettingsPage.SYNC)
        scope.launch {
            val current = withContext(Dispatchers.IO) {
                try { sync.pairedMailbox() } catch (e: Exception) { Problems.warn("sync", "Stored pairing not read", e); null }
            }
            val step = com.onpath.app.plansync.PairStep.of(current, com.onpath.app.sync.SyncKeys(secret).mailbox)
            Problems.info("sync", "Pairing link ($via): " + when (step) {
                com.onpath.app.plansync.PairStep.PAIR -> "asking"
                com.onpath.app.plansync.PairStep.ALREADY -> "already paired"
                com.onpath.app.plansync.PairStep.JOIN -> "another browser – asking (it joins this phone's mailbox)"
            })
            when (step) {
                com.onpath.app.plansync.PairStep.ALREADY -> {
                    pairEnded(PairResult.ALREADY) // stays on Browser sync, no "Paired. …" strip
                    screens.say(Txt.s(Res.string.sync_already_paired), NoticeKind.INFO, NoticePlace.SETTINGS)
                }
                // A-017: a second browser (laptop + desktop, a friend's PC) – the same check digits and Pair, no "unpair first"
                else -> if (!pairJoining) pairAsk = secret
            }
        }
    }

    /** Pair with another browser runs (A-017): the check digits stay, a spinner instead of Pair / Cancel. */
    var pairJoining by mutableStateOf(false)
        private set

    /**
     * "Pair": not paired yet → store the pairing, say hello, sync at once (the page shows "Paired with …" when the
     * browser answered). Paired already (A-017) → that browser is sent into this phone's mailbox (one JOIN item); the
     * phone's pairing stays. Both end in "Paired – …" with OK.
     */
    fun confirmPair() {
        val s = pairAsk ?: return
        screens.syncNote = null
        if (!sync.state.value.paired) { pairAsk = null; sync.pair(s) { ok -> pairEnded(if (ok) PairResult.PAIRED else PairResult.NOT_STORED) }; return }
        if (pairJoining) return
        pairJoining = true
        sync.join(s) { o ->
            pairJoining = false
            if (pairAsk === s) pairAsk = null // (Pair / Cancel are hidden meanwhile; a newer scanned code stays)
            when (o) {
                // unpaired meanwhile: a normal pairing
                null -> sync.pair(s) { ok -> pairEnded(if (ok) PairResult.PAIRED else PairResult.NOT_STORED) }
                is com.onpath.app.sync.SyncRound.Outcome.Done -> pairEnded(PairResult.JOINED)
                is com.onpath.app.sync.SyncRound.Outcome.Offline -> { pairEnded(PairResult.JOIN_FAILED); screens.say(Txt.s(Res.string.sync_join_offline), NoticeKind.ERROR, NoticePlace.SETTINGS) }
                is com.onpath.app.sync.SyncRound.Outcome.Ended -> { pairEnded(PairResult.JOIN_FAILED); screens.say(Txt.s(Res.string.sync_join_used), NoticeKind.ERROR, NoticePlace.SETTINGS) }
                is com.onpath.app.sync.SyncRound.Outcome.Refused -> { pairEnded(PairResult.JOIN_FAILED); screens.say(Txt.s(Res.string.sync_join_failed), NoticeKind.ERROR, NoticePlace.SETTINGS) }
            }
        }
    }

    /** Pair ended (main thread): made → back to the Plan map with "Paired. …" + OK (owner 2026-10-30); else stay. */
    private fun pairEnded(r: PairResult) {
        val s = PairLanding.after(r, landingNow())
        Problems.info("sync", "Pair ended: $r" + if (s.note != null) " – back to the Plan map with the note" else "")
        landOn(s)
    }

    private fun landingNow() = PairLanding.Screens(screens.showSettings, screens.settingsPage, screens.routesOpen, screens.syncNote)

    private fun landOn(s: PairLanding.Screens) {
        if (screens.settingsPage != s.settingsPage) screens.settingsPage = s.settingsPage
        if (screens.showSettings != s.settingsOpen) screens.showSettings = s.settingsOpen
        if (screens.routesOpen != s.routesOpen) screens.routesOpen = s.routesOpen
        screens.syncNote = s.note
    }

    fun cancelPair() { pairAsk = null; screens.syncNote = null; Problems.info("sync", "Pairing cancelled") }

    /**
     * Settings → Unpair (after the question): the mailbox ends for both sides; the routes stay everywhere. Then straight
     * back to the Plan map with "Unpaired. …" + OK (owner 2026-10-30, like Pair); the phone could not forget the
     * pairing → stay on the page with its red line.
     */
    fun unpair() {
        screens.syncNote = null
        sync.unpair { status ->
            if (status == PairLanding.UNPAIR_NOT_STORED) screens.say(Txt.s(Res.string.sync_unpair_failed), NoticeKind.ERROR, NoticePlace.SETTINGS)
            landOn(PairLanding.afterUnpair(status, landingNow()))
        }
    }
}
