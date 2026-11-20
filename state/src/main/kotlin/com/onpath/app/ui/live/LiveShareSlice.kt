package com.onpath.app.ui.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.live.LiveShareClient
import com.onpath.app.live.LiveSnapshot
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.Say
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.live_ended_by_server
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
    /** A tour records right now – only then sharing is offered. */
    fun recording(): Boolean
    /** Android's share sheet with this text (Signal, WhatsApp …). */
    fun shareText(text: String)
    /** Wall clock, ms. */
    fun nowMs(): Long
}

/**
 * Live sharing as the Settings opened from Navigate show it (docs/design/live-tracking.md §2, §14.2; owner 2026-11-20:
 * "in der Navigation bei den Einstellungen, als erster Eintrag mit Symbol"): the state, the actions, the name question –
 * over [LiveShareClient], whose snapshot the app hands in with [onSnapshot] (the lane publishes it; the slice never
 * touches the uploader). Sharing never asks anything during the tour and never shows an error box: trouble is the grey
 * state; only a sharing the server ended is said once, as a quiet line.
 */
class LiveShareSlice(
    private val client: LiveShareClient,
    private val prefs: LivePrefs,
    private val host: LiveShareHost,
    private val say: Say,
) {
    private var snapshot by mutableStateOf(LiveSnapshot.OFF)
    /** "Live teilen" was tapped and the link has not been handed to the share sheet yet. */
    private var linkWanted = false
    private var storedName by mutableStateOf(prefs.liveName)

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

    /** The state; [LiveSign.WAITING] also when the last good upload is older than [FRESH_MS] (read with the host's clock). */
    val sign: LiveSign get() {
        val s = snapshot
        return when {
            !s.sharing -> LiveSign.NONE
            s.waiting -> LiveSign.WAITING
            s.lastOkMs?.let { host.nowMs() - it > FRESH_MS } == true -> LiveSign.WAITING
            else -> LiveSign.LIVE
        }
    }

    /** The actions offered now; empty when no tour records and nothing is shared. */
    val actions: List<LiveAction> get() = when {
        snapshot.sharing -> listOf(LiveAction.SHARE_AGAIN, LiveAction.STOP)
        host.recording() -> listOf(LiveAction.SHARE)
        else -> emptyList()
    }

    /** "Live teilen": the first time the name is asked, then the share sheet opens with the link. */
    fun share() {
        if (snapshot.sharing) { shareAgain(); return }
        if (!host.recording()) return
        if (storedName == null) { editingName = false; askName = true; return }
        start(storedName.orEmpty())
    }

    /** The name question answered: [name] is kept (trimmed, may be empty); before the first share the sharing starts. */
    fun nameGiven(name: String) {
        val edit = editingName
        askName = false; editingName = false
        val n = name.trim().take(MAX_NAME)
        prefs.liveName = n; storedName = n
        if (!edit && host.recording() && !snapshot.sharing) start(n)
    }

    /** The name question closed without an answer: nothing is shared, nothing stored – it is asked again next time. */
    fun nameCancelled() { askName = false; editingName = false }

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
        if (s.endedByServer) {
            // once, quietly: the link in the chat is dead now – the tour itself is untouched
            say.say(Txt.s(Res.string.live_ended_by_server), NoticeKind.INFO, NoticePlace.MAP)
            client.liveSeen()
            linkWanted = false
        }
        if (linkWanted && s.sharing && s.link != null) { linkWanted = false; send(s.link!!) }
    }

    private fun start(name: String) {
        linkWanted = true
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
