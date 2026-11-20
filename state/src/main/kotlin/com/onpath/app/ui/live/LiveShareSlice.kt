package com.onpath.app.ui.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.live.LiveShareClient
import com.onpath.app.live.LiveSnapshot
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.live_share_text

/** The state of the sharing as its row in the on-the-way Settings shows it. */
enum class LiveSign {
    /** Not sharing. */
    NONE,
    /** The last upload went through not long ago: the green dot. */
    LIVE,
    /** Uploads do not get through (or the last good one is old): the grey dot, "wartet auf Netz". */
    WAITING,
}

/** What the user can do about the sharing right now, in the order shown. */
enum class LiveAction { SHARE, SHARE_AGAIN, STOP }

/** The name on the viewer page ("Tour von …"), remembered on the phone; asked once, may stay empty. */
interface LivePrefs {
    /** The name; null = never asked. */
    var liveName: String?
}

/** What [LiveShareSlice] asks of the app. */
interface LiveShareHost {
    /** Android's share sheet with this text (Signal, WhatsApp …). */
    fun shareText(text: String)
    /** Wall clock, ms. */
    fun nowMs(): Long
}

/**
 * Live sharing as the Settings opened from Navigate show it (docs/design/live-tracking.md §2, §14.2; owner 2026-11-20:
 * "in der Navigation bei den Einstellungen, als erster Eintrag mit Symbol"): the state, the actions, the name question –
 * over [LiveShareClient], whose snapshot the app hands in with [onSnapshot] (the lane publishes it; the slice never
 * touches the uploader) and the recording's state with [onRecording]; the app calls [tick] now and then so the 12-min
 * rule turns the sign grey without a new snapshot. Sharing never asks anything during the tour and never shows an error
 * box: trouble is the grey state; only a sharing the server ended is shown, as a line in the sharing's Settings card
 * ([ended]) until that card was seen.
 */
class LiveShareSlice(
    private val client: LiveShareClient,
    private val prefs: LivePrefs,
    private val host: LiveShareHost,
) {
    private var snapshot by mutableStateOf(LiveSnapshot.OFF)
    private var recording by mutableStateOf(false)
    /** The last good upload is older than [FRESH_MS] (or there was none in this process) – set by [onSnapshot] and [tick]. */
    private var stale by mutableStateOf(false)
    /** When "Teilen" started the running share in this process (wall clock); null = it came from an earlier process. */
    private var startedHereMs: Long? = null
    /** "Live teilen" was tapped and the link has not been handed to the share sheet yet. */
    private var linkWanted = false
    private var storedName by mutableStateOf(prefs.liveName)
    /** The Settings card showed [ended] (the lane was told: [LiveShareClient.liveSeen]). */
    private var endedSeen = false
    /** The lane was told; a snapshot still saying "ended by the server" is from before that. */
    private var endedAcked = false

    /** The server ended the sharing (expired, stopped elsewhere): the Settings card says so until it was seen there. */
    var ended by mutableStateOf(false)
        private set

    /** The name question is open – before the first share, or from the "Name" row ([editName]). */
    var askName by mutableStateOf(false)
        private set
    /** The open name question only changes the name ([editName]); it starts no sharing. */
    var editingName by mutableStateOf(false)
        private set

    /** The name was asked before (the "Name" row is shown then). */
    val nameKnown: Boolean get() = storedName != null

    /** The name as stored (empty when none). */
    val name: String get() = storedName.orEmpty()

    /** The state; [LiveSign.WAITING] also when the last good upload is older than [FRESH_MS] (as of the last [tick]). */
    val sign: LiveSign get() {
        val s = snapshot
        return when {
            !s.sharing -> LiveSign.NONE
            s.waiting || stale -> LiveSign.WAITING
            else -> LiveSign.LIVE
        }
    }

    /** The actions offered now; empty when no tour records and nothing is shared. */
    val actions: List<LiveAction> get() = when {
        snapshot.sharing -> listOf(LiveAction.SHARE_AGAIN, LiveAction.STOP)
        recording -> listOf(LiveAction.SHARE)
        else -> emptyList()
    }

    /** A tour records (the recording service's state changed). */
    fun onRecording(on: Boolean) { recording = on }

    /** The clock moved on: the sign turns grey when the last good upload got old. */
    fun tick() { stale = isStale() }

    /** "Live teilen": the first time the name is asked, then the share sheet opens with the link. */
    fun share() {
        if (snapshot.sharing) { shareAgain(); return }
        if (!recording) return
        if (storedName == null) { editingName = false; askName = true; return }
        start(storedName.orEmpty())
    }

    /** The name question answered: [name] is kept (trimmed, may be empty); before the first share the sharing starts. */
    fun nameGiven(name: String) {
        val edit = editingName
        askName = false; editingName = false
        val n = name.trim().take(MAX_NAME)
        prefs.liveName = n; storedName = n
        if (!edit && recording && !snapshot.sharing) start(n)
    }

    /** The name question closed without an answer: nothing is shared, nothing stored – it is asked again next time. */
    fun nameCancelled() { askName = false; editingName = false }

    /** The Settings page closed (by the user or by the app): an open name question goes, a seen "ended" line too. */
    fun settingsClosed() {
        if (askName) nameCancelled()
        if (endedSeen) ended = false
    }

    /** The Settings card showed [ended]: now the lane may forget it. */
    fun endedShown() {
        if (!ended || endedSeen) return
        endedSeen = true; endedAcked = true
        client.liveSeen()
    }

    /** The "Name" row: change the name for the next share (a running share keeps the name it started with). */
    fun editName() { editingName = true; askName = true }

    /** "Link nochmal teilen": the same link for a second person. */
    fun shareAgain() { snapshot.link?.let(::send) }

    /** "Teilen beenden": the state goes at once, the server's copy is deleted. */
    fun stop() {
        linkWanted = false
        client.liveStop()
    }

    /** The lane published a new snapshot (the app's collector calls this on the main thread). */
    fun onSnapshot(s: LiveSnapshot) {
        snapshot = s
        if (!s.sharing) startedHereMs = null
        stale = isStale()
        if (s.endedByServer) {
            // the link in the chat is dead now – the tour itself is untouched; said in the Settings card, not as a box
            linkWanted = false
            if (!endedAcked && !ended) { ended = true; endedSeen = false }
        } else endedAcked = false
        if (linkWanted && s.sharing && s.link != null) { linkWanted = false; send(s.link!!) }
    }

    private fun isStale(): Boolean {
        val s = snapshot
        if (!s.sharing) return false
        val ok = s.lastOkMs ?: startedHereMs ?: return true
        return host.nowMs() - ok > FRESH_MS
    }

    private fun start(name: String) {
        linkWanted = true
        startedHereMs = host.nowMs()
        ended = false
        client.liveStart(name)
        // the lane may have published the link already (a share that was running)
        client.live.value.let { if (it.sharing && it.link != null) onSnapshot(it) }
    }

    private fun send(link: String) = host.shareText(Txt.s(Res.string.live_share_text, link))

    companion object {
        /** A good upload younger than this = "live" (uploads come every 2 min moving, every 10 min at rest – plus slack). */
        const val FRESH_MS = 12 * 60_000L
        const val MAX_NAME = 40
    }
}
