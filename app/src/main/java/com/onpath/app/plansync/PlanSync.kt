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
import com.onpath.strings.sync_copy
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
 * What a scanned pairing code leads to (A-017): not paired → [PAIR]; the browser already paired → [ALREADY] (a grey
 * line); another browser while paired → [JOIN] (the check digits and Pair as for a first pairing; Pair sends that
 * browser into this phone's mailbox – never "unpair first").
 */
enum class PairStep {
    PAIR, JOIN, ALREADY;

    companion object {
        /** [current] = the stored pairing's mailbox (null = not paired), [incoming] = the scanned code's mailbox. */
        fun of(current: String?, incoming: String): PairStep = when (current) {
            null -> PAIR
            incoming -> ALREADY
            else -> JOIN
        }
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
 * skipped and runs when the tour has ended ([request] from the view model); only the pairing handshake right after a
 * scan runs during a tour (the phone's hello + one pull, once – field bug 2026-10-31). No sync while the app is closed
 * or in the background.
 */
class PlanSync(
    context: Context,
    private val store: TourStore,
    private val recording: () -> Boolean,
    /** Main thread: a pass ended ([Report.manual] = the pass right after pairing). */
    private val onReport: (Report) -> Unit,
    private val server: String = SERVER,
    dir: File = File(context.filesDir, SyncFiles.DIR),
    /** This phone's routing (fingerprint + rd5 dates) for the plans it saves and the synced plans it opens. */
    private val routing: RoutingStamp = FileRoutingStamp({ n -> context.assets.open("brouter/$n").use { it.readBytes() } },
        { com.onpath.app.data.DataFiles(context.applicationContext).segmentsDir }),
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
    /** A scan arms it once: the next run is the handshake even during a tour ([PlanSyncPass.handshake]), never a loop. */
    private val gate = PassGate()
    /** Whether the last run during a tour left the routes for after it ([State.afterTour]); only the worker writes it. */
    @Volatile private var afterTour = false

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
        /**
         * Paired: is a browser at the other end ([BrowserLink], from the browser hellos)? [BrowserLink.UNPAIRED] = the
         * browser unpaired itself (☰ "Pair with browser" + grey "Unpaired in the browser"). Not paired: unused.
         */
        val browser: BrowserLink = BrowserLink.CONNECTED,
        /** A tour is recording and the routes wait for its end (grey "Routes sync after the tour"). */
        val afterTour: Boolean = false,
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
        /** [count] routes stayed unsent for [StuckOutbox.PASSES] passes in a row (review sync M8: was "synced"). */
        data class Stuck(val count: Int) : Problem
    }

    /** Passes in a row that ended with routes still waiting to be sent. */
    private var stuckPasses = 0

    /** A pass, pairing or unpairing ended. */
    data class Report(val manual: Boolean, val result: PlanSyncPass.Result?, val ended: Boolean = false, val error: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** This phone's name for the browser (its hello): the user's device name, else the model ([deviceName]). */
    val deviceName: String by lazy { deviceName(app) }

    private fun pass() = PlanSyncPass(files, store, ::mailbox, ::randomBytes, System::currentTimeMillis, deviceName,
        { if (it == SyncItem.TRACK) Txt.s(Res.string.sync_untitled_track) else Txt.s(Res.string.sync_untitled_route) }, JcaAead,
        copyLabel(Txt.s(Res.string.sync_copy)), routing)

    private fun mailbox(keys: SyncKeys): Mailbox = mailboxForTests?.invoke(keys) ?: HttpMailbox(server, keys)
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
        val step = gate.next(recording())
        val handshake = step == PassGate.Step.HANDSHAKE
        if (step == PassGate.Step.SKIP) {
            if (!quiet) Problems.trace("sync", "Sync ($why) skipped: a tour is recording – after the tour")
            if (!afterTour) { afterTour = true; _state.value = _state.value.copy(afterTour = true) }
            if (manual) report(Report(true, null))
            return
        }
        _state.value = _state.value.copy(running = true)
        // during a tour only the pairing handshake right after a scan: the phone's hello + one pull, no routes
        if (handshake) Problems.info("sync", "Sync ($why) during a tour: the pairing handshake only – routes after the tour")
        val r = try { if (handshake) pass().handshake() else pass().run(recording) } catch (e: Exception) {
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
                // a new mailbox: every browser hello in it is the scanned browser's (no scan to wait on, PairScan)
                files.clearScan()
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
            gate.arm() // the browser waits for this phone's hello – also during a tour
            request("paired", manual = true)
        }
    }

    /**
     * Pair with another browser while paired (A-017): the scanned code's browser is sent into this phone's mailbox
     * ([PlanSyncPass.join] – one JOIN item); the phone's own pairing stays. Main-thread callback with the outcome
     * (null = not paired any more – the caller pairs the normal way). One small write, also during a tour (no sync).
     */
    fun join(browserSecret: ByteArray, done: (SyncRound.Outcome?) -> Unit) {
        worker.execute {
            // the browser hellos known at the scan: an old one is no sign of the scanned browser (owner 2026-10-31)
            val scan = try { PairScan.of(files.ledger(), files.device(::randomBytes), System.currentTimeMillis()) } catch (e: Exception) {
                Problems.warn("sync", "Browser hellos at the scan not read – any new hello counts", e); PairScan(System.currentTimeMillis())
            }
            val o = try { pass().join(browserSecret) } catch (e: Exception) {
                Problems.error("sync", "The other browser could not be paired", e); SyncRound.Outcome.Refused(-2, null)
            }
            Problems.info("sync", "Pair another browser (check ${SyncKeys(browserSecret).check}): " + when (o) {
                null -> "not paired here – pairing normally"
                is SyncRound.Outcome.Done -> "it joins this phone's mailbox"
                is SyncRound.Outcome.Offline -> "offline"
                is SyncRound.Outcome.Ended -> "its code is no longer valid"
                is SyncRound.Outcome.Refused -> "server refused (${o.status}${o.error?.let { ": $it" } ?: ""})"
            })
            if (o is SyncRound.Outcome.Done) {
                // waiting for the joined browser's hello (kept across a restart): never "connected" from an old hello,
                // never "unpaired" meanwhile; a pass soon picks the hello up – during a tour once, as the handshake
                safely("scan note") { files.saveScan(scan) }
                _state.value = _state.value.copy(browser = BrowserLink.WAITING)
                gate.arm()
                if (!busy()) request("joined", delayMs = JOIN_PULL_DELAY_MS)
            }
            main.post { done(o) }
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

    /**
     * A saved route was opened as the plan ([points]): its synced plan item's routing against this phone's
     * ([SyncRouting.opened] – the grey "planned with other routing" line, tiles to check for newer data); null when the
     * route is not synced as a plan. Reads the sync files: background only.
     */
    fun opened(uuid: String, points: List<com.onpath.app.routing.GeoPoint>): SyncRouting.Opened? {
        if (uuid.isEmpty() || files.pairing() == null) return null
        val id = files.links().itemOf(uuid) ?: return null
        val item = files.ledger().get(id)?.takeIf { it.kind == SyncItem.PLAN && !it.deleted } ?: return null
        return SyncRouting.opened(item, files.device(::randomBytes), routing.fingerprint(), points, routing.days(), System.currentTimeMillis())
    }

    /** The mailbox of the stored pairing (same browser again?), or null. Reads a small file: background or rare taps. */
    fun pairedMailbox(): String? = files.pairing()?.secret?.let { SyncKeys(it).mailbox }

    private fun refreshState(r: PlanSyncPass.Result? = null) {
        val p = files.pairing()
        var peer = r?.peer
        // not read: as before (never a false "unpaired")
        var browser = BrowserLink.CONNECTED
        if (p != null) try {
            val ledger = files.ledger()
            val me = files.device(::randomBytes)
            if (peer == null) peer = ledger.devices(me).firstOrNull()?.name
            var scan = files.scan()
            if (scan != null && scan.expired(System.currentTimeMillis(), r?.outcome is SyncRound.Outcome.Done)) {
                Problems.info("sync", "No new browser hello ${PairScan.WAIT_MS / 60_000} min after the scan – the browsers in the mailbox count again")
                safely("scan note") { files.clearScan() }
                scan = null
            }
            browser = BrowserLink.of(ledger, me, scan)
            // the scanned browser said hello: from now on the plain rule (it may unpair itself later)
            if (scan != null && browser == BrowserLink.CONNECTED) safely("scan note") { files.clearScan() }
        } catch (e: Exception) { Problems.warn("sync", "Paired browser not read", e) }
        if (p != null && _state.value.paired && browser != _state.value.browser) Problems.info("sync", "Browser at the other end: $browser")
        if (p == null) afterTour = false else if (r != null) afterTour = r.skippedForRecording
        val problem: Problem? = when (val o = r?.outcome) {
            null -> null
            is SyncRound.Outcome.Offline -> Problem.Offline
            is SyncRound.Outcome.Refused -> Problem.Refused(o.status, o.error)
            is SyncRound.Outcome.Ended -> null
            is SyncRound.Outcome.Done -> {
                if (o.restarted) Problems.warn("sync", "The browser mailbox had lost its history (idle purge / reset) – this phone sent everything again")
                if (o.tooLarge > 0) Problems.warn("sync", "${o.tooLarge} item(s) refused by the server as too large – they stay on the phone")
                stuckPasses = StuckOutbox.next(stuckPasses, o.left)
                if (stuckPasses == StuckOutbox.PASSES) Problems.warn("sync", "${o.left} item(s) not sent for $stuckPasses passes in a row")
                when {
                    r.failed > 0 -> Problem.NotSaved(r.failed)
                    r.unreadable > 0 -> Problem.NewerApp(r.unreadable)
                    StuckOutbox.stuck(stuckPasses) -> Problem.Stuck(o.left)
                    else -> null
                }
            }
        }
        _state.value = State(paired = p != null, peer = if (p == null) null else peer, lastOkMs = if (p == null) 0 else files.lastOk(),
            running = false, problem = if (p == null) null else problem, check = p?.secret?.let { SyncKeys(it).check },
            sinceMs = p?.since ?: 0, checkedMs = checkedMs, ended = p == null && files.ended() > 0, browser = browser,
            afterTour = afterTour)
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

        /**
         * Emulator tests only (Release-Rundgang, docs/release-rundgang.md): a mailbox in memory instead of [SERVER], so
         * the flow "a plan from the browser arrives" never depends on the network or the server's limits. Null in the app.
         */
        @androidx.annotation.VisibleForTesting @Volatile var mailboxForTests: ((SyncKeys) -> Mailbox)? = null
        const val CHANGE_DELAY_MS = 1_500L
        /** After a JOIN: a pass this much later picks up the joined browser's hello (it reads the JOIN on its next look). */
        const val JOIN_PULL_DELAY_MS = 20_000L

        /** The phone's name: Settings → About phone → Device name (Settings.Global.DEVICE_NAME), else Build.MODEL. */
        fun deviceName(context: Context): String = deviceName(
            { android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME) },
            android.os.Build.MODEL)

        /**
         * The name the browser shows for this phone (A-017: two "Pixel 8" in one browser are told apart by the name the
         * user gave): [userName] when it gives one, else [model], else "Android". Never fails – a fallback is noted (INFO).
         */
        fun deviceName(userName: () -> String?, model: String?): String {
            val user = try { userName()?.trim()?.takeIf { it.isNotEmpty() } } catch (e: Exception) {
                Problems.info("sync", "Device name not readable – the model is used for the browser", e); null
            }
            if (user != null) return user
            val m = model?.trim()?.takeIf { it.isNotEmpty() }
            Problems.info("sync", "No device name set – the browser sees " + (if (m != null) "the model" else "\"Android\""))
            return m ?: "Android"
        }

        /** The copy suffix as shown on this phone (" (Kopie)") from the localized word ("Kopie"); the item keeps " (copy)". */
        fun copyLabel(word: String): String = word.trim().takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: com.onpath.app.sync.SyncMerge.COPY

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
