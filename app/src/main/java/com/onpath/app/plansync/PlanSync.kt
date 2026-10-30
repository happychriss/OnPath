package com.onpath.app.plansync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import com.onpath.app.debug.Problems
import com.onpath.app.routing.PlanLinks
import com.onpath.app.sync.Aead
import com.onpath.app.sync.Mailbox
import com.onpath.app.sync.SyncItem
import com.onpath.app.sync.SyncKeys
import com.onpath.app.sync.SyncRound
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.sync_untitled_route
import com.onpath.strings.sync_untitled_track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** AES-256-GCM from the platform (javax.crypto, hardware AES) – byte-equal to the shared [com.onpath.app.sync.AesGcm]. */
object JcaAead : Aead {
    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray) =
        javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }

    override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        cipher(javax.crypto.Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plain)

    override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = try {
        cipher(javax.crypto.Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(sealed)
    } catch (_: javax.crypto.AEADBadTagException) {
        null // expected: another key / a changed blob – the caller counts it as unreadable
    }
}

/**
 * Plan sync phone ⇄ browser (web planner phase 2, docs/design/web-sync.md, Drive order A-007): the app's side around
 * [PlanSyncPass] – one background thread, the triggers and the state Settings shows.
 *
 * Triggers: after a change of a saved route / imported track ([changed], a short delay gathers several), at start
 * and when the app comes back ([request]), when the network comes back, and about every minute while the app stays
 * open on My routes / Browser sync ([poll], rule in [SyncPoll]), and right away when My routes or Browser sync
 * opens ([SyncPoll.opened], debounced). **Never during a recording** – the pass is
 * skipped and runs when the tour has ended ([request] from the view model). No sync while the app is closed or in the
 * background.
 */
class PlanSync(
    context: Context,
    private val store: TourStore,
    private val recording: () -> Boolean,
    /** Main thread: a pass ended ([Report.manual] = the pass right after pairing). */
    private val onReport: (Report) -> Unit,
    private val server: String = SERVER,
    dir: File = File(context.filesDir, SyncFiles.DIR),
) {
    private val app = context.applicationContext
    val files = SyncFiles(dir)
    private val rnd = SecureRandom()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "plan-sync").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pendingRun: ScheduledFuture<*>? = null
    @Volatile private var manualWaiting = false
    /** When the last pass ended in this run of the app ([State.checkedMs]); only the worker thread writes it. */
    @Volatile private var checkedMs = 0L

    /** What Settings → Browser sync shows. */
    data class State(
        val paired: Boolean = false,
        /** The browser's name from its hello ("Chrome on Linux"); null = none yet. */
        val peer: String? = null,
        val lastOkMs: Long = 0,
        val running: Boolean = false,
        val problem: Problem? = null,
        /** The six check digits of the pairing ("395 514"). */
        val check: String? = null,
        /** When the pairing was made (ms; 0 = unknown / not paired). */
        val sinceMs: Long = 0,
        /** When the last pass ended in this run of the app, well or not (ms; 0 = none yet). */
        val checkedMs: Long = 0,
        /** The pairing was ended in the browser and Settings → Browser sync was not opened since (☰ shows it amber). */
        val ended: Boolean = false,
    )

    /** Why the last pass did not end well. */
    sealed interface Problem {
        data object Offline : Problem
        data class Refused(val status: Int, val error: String?) : Problem
        /** Items from a newer app (update OnPath). */
        data class NewerApp(val count: Int) : Problem
        /** Routes from the browser could not be saved on the phone (tried again next time). */
        data class NotSaved(val count: Int) : Problem
        /** The sync files could not be written (storage full). */
        data object Storage : Problem
    }

    /** A pass, pairing or unpairing ended. */
    data class Report(val manual: Boolean, val result: PlanSyncPass.Result?, val ended: Boolean = false, val error: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private fun pass() = PlanSyncPass(files, store, ::mailbox, ::randomBytes, System::currentTimeMillis, deviceName(),
        { if (it == SyncItem.TRACK) Txt.s(Res.string.sync_untitled_track) else Txt.s(Res.string.sync_untitled_route) }, JcaAead)

    private fun mailbox(keys: SyncKeys): Mailbox = HttpMailbox(server, keys)
    private fun randomBytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    /** Reads the stored pairing (background) – the state for Settings; then a first pass. */
    fun start() {
        worker.execute { safely("start") { refreshState() } }
        listenForNetwork()
        request("app start")
    }

    /** A saved route / imported track was saved, renamed, deleted or restored: sync soon. */
    fun changed(why: String) = request(why, delayMs = CHANGE_DELAY_MS)

    /** A pass runs or waits to run. */
    fun busy(): Boolean = pendingRun?.isDone == false

    /** The regular pass while the app stays open ([SyncPoll]): only when no pass runs or waits. True = asked for. */
    fun poll(why: String): Boolean {
        if (busy()) return false
        request(why, quiet = true)
        return true
    }

    /** Sync now ([manual]: right after pairing – its result is said in Settings → Browser sync). */
    /** [quiet]: a regular poll – it goes into the event log only when it moved something or failed (one a minute). */
    fun request(why: String, manual: Boolean = false, delayMs: Long = 0, quiet: Boolean = false) {
        if (manual) manualWaiting = true
        pendingRun?.cancel(false)
        pendingRun = try { worker.schedule({ run(why, quiet) }, delayMs, TimeUnit.MILLISECONDS) } catch (e: Exception) {
            Problems.warn("sync", "Sync not scheduled ($why)", e); null
        }
    }

    private fun run(why: String, quiet: Boolean = false) {
        val manual = manualWaiting
        manualWaiting = false
        if (files.pairing() == null) { if (manual) report(Report(true, null)); return }
        if (recording()) {
            if (!quiet) Problems.trace("sync", "Sync ($why) skipped: a tour is recording – after the tour")
            if (manual) report(Report(true, null))
            return
        }
        _state.value = _state.value.copy(running = true)
        val r = try { pass().run(recording) } catch (e: Exception) {
            Problems.error("sync", "Sync files could not be written", e)
            checkedMs = System.currentTimeMillis()
            _state.value = _state.value.copy(running = false, problem = Problem.Storage, checkedMs = checkedMs)
            report(Report(manual, null, error = true))
            return
        }
        val ended = r?.outcome is SyncRound.Outcome.Ended
        checkedMs = System.currentTimeMillis()
        if (ended) {
            Problems.info("sync", "Sync: the pairing was ended in the browser – plans stay on the phone")
            safely("forget") { pass().forgetHere() }
            safely("ended note") { files.saveEnded(System.currentTimeMillis()) }
        } else if (!(quiet && idle(r))) Problems.trace("sync", "Sync ($why): ${describe(r)}")
        refreshState(r)
        report(Report(manual, r, ended = ended))
    }

    /**
     * The pairing from a scanned / opened link: stored, hello, synced at once. Main-thread callback with the report;
     * [stored] (main thread) as soon as the pairing is stored – before the first pass – false when it could not be.
     */
    fun pair(secret: ByteArray, stored: (Boolean) -> Unit = {}) {
        manualWaiting = true
        worker.execute {
            try {
                // a ledger from an earlier pairing: its plans upload again, nothing else of the old mailbox stays
                val l = files.ledger(); l.forget(); files.saveLedger(l)
                files.savePairing(secret, System.currentTimeMillis())
                files.clearEnded()
                Problems.info("sync", "Paired with a browser (check ${SyncKeys(secret).check})")
            } catch (e: Exception) {
                Problems.error("sync", "Pairing could not be stored", e)
                manualWaiting = false
                _state.value = _state.value.copy(problem = Problem.Storage)
                report(Report(true, null, error = true))
                main.post { stored(false) }
                return@execute
            }
            refreshState()
            main.post { stored(true) }
            request("paired", manual = true)
        }
    }

    /** Settings → Unpair (asked first): the mailbox ends for every device, the pairing is forgotten here. */
    fun unpair(done: (status: Int) -> Unit) {
        worker.execute {
            val status = try { pass().unpair() } catch (e: Exception) {
                Problems.error("sync", "Unpairing failed", e); -2
            }
            if (status >= -1) Problems.info("sync", "Unpaired (server answer $status)")
            refreshState()
            main.post { done(status) }
        }
    }

    /** Settings → Browser sync was opened: an "ended in the browser" was seen – the ☰ item is no longer amber. */
    fun endedSeen() {
        if (!_state.value.ended) return
        worker.execute { safely("ended note") { files.clearEnded() }; safely("state") { refreshState() } }
    }

    /** The mailbox of the stored pairing (same browser again?), or null. Reads a small file: background or rare taps. */
    fun pairedMailbox(): String? = files.pairing()?.secret?.let { SyncKeys(it).mailbox }

    private fun refreshState(r: PlanSyncPass.Result? = null) {
        val p = files.pairing()
        val peer = r?.peer ?: if (p == null) null else try {
            files.ledger().devices(files.device(::randomBytes)).firstOrNull()?.name
        } catch (e: Exception) { Problems.warn("sync", "Paired browser name not read", e); null }
        val problem: Problem? = when (val o = r?.outcome) {
            null -> null
            is SyncRound.Outcome.Offline -> Problem.Offline
            is SyncRound.Outcome.Refused -> Problem.Refused(o.status, o.error)
            is SyncRound.Outcome.Ended -> null
            is SyncRound.Outcome.Done -> when {
                r.failed > 0 -> Problem.NotSaved(r.failed)
                r.unreadable > 0 -> Problem.NewerApp(r.unreadable)
                else -> null
            }
        }
        _state.value = State(paired = p != null, peer = if (p == null) null else peer, lastOkMs = if (p == null) 0 else files.lastOk(),
            running = false, problem = if (p == null) null else problem, check = p?.secret?.let { SyncKeys(it).check },
            sinceMs = p?.since ?: 0, checkedMs = checkedMs, ended = p == null && files.ended() > 0)
    }

    private fun report(r: Report) = main.post { try { onReport(r) } catch (e: Exception) { Problems.warn("sync", "Sync result not shown", e) } }

    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Problems.warn("sync", "Sync $what failed", e) }
    }

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** The network came back: sync (the first call right after registering is the start trigger's). */
    private fun listenForNetwork() {
        try {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (_state.value.problem == Problem.Offline) request("network back", delayMs = 2_000)
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            Problems.warn("sync", "Network changes not watched; sync runs at start and after changes", e)
        }
    }

    fun stop() {
        try { netCallback?.let { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } } catch (e: Exception) {
            Problems.warn("sync", "Network watch not removed", e)
        }
        netCallback = null
        worker.shutdown()
    }

    companion object {
        /** The mailbox's host (docs/design/web-sync.md §8: the planner's host, no CORS) – the one constant. */
        const val SERVER = PlanLinks.BASE
        const val CHANGE_DELAY_MS = 1_500L

        fun deviceName(): String = android.os.Build.MODEL?.trim()?.takeIf { it.isNotEmpty() } ?: "Android"

        /** A pass that reached the server and moved nothing. */
        fun idle(r: PlanSyncPass.Result?): Boolean {
            val o = r?.outcome as? SyncRound.Outcome.Done ?: return false
            return o.pulled == 0 && o.pushed == 0 && o.unreadable == 0 && r.added == 0 && r.updated == 0 && r.binned == 0 &&
                r.failed == 0 && !r.toursChanged && !r.skippedForRecording
        }

        fun describe(r: PlanSyncPass.Result?): String = when (val o = r?.outcome) {
            null -> "not paired"
            is SyncRound.Outcome.Done -> "read ${o.pulled}, sent ${o.pushed}, ${o.left} left, ${o.unreadable} unreadable; " +
                "${r.added} new, ${r.updated} changed, ${r.binned} to the bin, ${r.failed} failed" +
                (if (r.skippedForRecording) ", import after the tour" else "")
            is SyncRound.Outcome.Offline -> "offline"
            is SyncRound.Outcome.Ended -> "pairing ended in the browser"
            is SyncRound.Outcome.Refused -> "server refused (${o.status}${o.error?.let { ": $it" } ?: ""})"
        }
    }
}
