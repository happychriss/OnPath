package com.onpath.app.live

import com.onpath.app.debug.Problems
import com.onpath.app.routing.GeoPoint
import com.onpath.app.sync.Aead
import com.onpath.app.sync.AesGcm
import com.onpath.app.sync.B64
import com.onpath.app.sync.Sha256

/** The server's answer to one call: the HTTP status; 0 = no network / timeout / no answer. */
data class LiveReply(val code: Int, val retryAfterS: Int? = null) {
    val ok: Boolean get() = code == 200 || code == 201
    /** The box is gone for good (stopped, expired, another writer's): never ask again. */
    val gone: Boolean get() = code == 410 || code == 403 || code == 404
    /** The server is busy: try again when it says, or later. */
    val busy: Boolean get() = code == 429 || code in 500..599
}

/** One sealed item of a push: its name ("h", "r", "t0000" …) and the blob. */
class LivePush(val name: String, val blob: ByteArray)

/**
 * One tour's box on the server (docs/design/live-tracking.md §6) as the phone uses it. The Android side implements it
 * over HTTP. **Never throws** – a failure is a [LiveReply] (code 0 without an answer); the uploader guards itself
 * against an implementation that throws all the same.
 */
interface LiveBox {
    /** `POST /live/v1/<box>` with the hex SHA-256 of the read and the write token: 201 / 200, 403 another writer, 410 ended. */
    fun claim(readHash: String, writeHash: String): LiveReply
    /** `POST /live/v1/<box>/push`: all [items] in one request; [ended] with the tour's last push. */
    fun push(items: List<LivePush>, ended: Boolean): LiveReply
    /** `DELETE /live/v1/<box>`: everything gone, the link answers "no longer shared". */
    fun delete(): LiveReply
}

/** Opens the box [boxId] for writing with [writeToken] (no network yet). */
fun interface LiveBoxes {
    fun open(boxId: String, writeToken: String): LiveBox
}

/** A recorded point with its running number in the tour (the uploader's cursor). */
class LiveTrackPoint(val seq: Int, val point: LivePoint)

/** The followed line and a number that changes whenever the line does (a re-route, another plan); never [LiveShare.NO_ROUTE]. */
class LiveRoute(val version: Int, val line: List<GeoPoint>)

/**
 * The recording as the uploader sees it – **read-only**: nothing here may write to the tour, wait for the recorder or
 * take a lock the recorder's point writing takes (§7).
 */
interface LiveTrack {
    /** The tour's points with a number above [seq], in order, at most [max]. */
    fun pointsAfter(tourId: Long, seq: Int, max: Int): List<LiveTrackPoint>
    /**
     * The tour's status now (state, fix, numbers, battery, activity); null when it is not known (the tour does not
     * run in this process). The uploader fills in name, counter, next upload and the chunk count itself.
     */
    fun head(tourId: Long): LiveHead?
    /** The line that is followed now; null = nothing followed. */
    fun route(tourId: Long): LiveRoute?
}

/** Keeps the share's state as one text ([LiveShare.toText]); null = none. The Android side writes it atomically. */
interface LiveStore {
    fun read(): String?
    fun write(text: String?)
}

/**
 * What the phone remembers of a shared tour, so a killed process goes on with the same link (§7.5).
 * [cursorSeq] = the last point that is safely on the server (-1 = none), [nextChunk] = the chunk number the points
 * after it get. Both move only after the server's 200.
 */
data class LiveShare(
    val secret: ByteArray, val writeToken: String, val tourId: Long, val name: String,
    val claimed: Boolean = false, val cursorSeq: Int = -1, val nextChunk: Int = 0,
    /** The route version that is on the server; [NO_ROUTE] = none. */
    val routeVersion: Int = NO_ROUTE,
    /** Heads sent so far. */
    val counter: Int = 0,
    /** The tour ended: send what is left, the last head with "ended", then forget the share. */
    val ending: Boolean = false,
    /** "Stop sharing": delete the box, then forget the share. */
    val stopping: Boolean = false,
    /** The last head that reached the server (plain item bytes) – the base of the final head when the tour is gone. */
    val lastHead: ByteArray? = null,
) {
    /** One line: `onpath-live 1 <fields>`; a newer version may append fields. */
    fun toText(): String = listOf(
        MAGIC, "1", B64.encode(secret), writeToken, tourId.toString(), if (claimed) "1" else "0", cursorSeq.toString(),
        nextChunk.toString(), routeVersion.toString(), counter.toString(),
        ((if (ending) 1 else 0) or (if (stopping) 2 else 0)).toString(),
        B64.encode(name.encodeToByteArray()).ifEmpty { "-" }, lastHead?.let(B64::encode)?.ifEmpty { null } ?: "-",
    ).joinToString(" ")

    override fun equals(other: Any?) = other is LiveShare && toText() == other.toText()
    override fun hashCode() = toText().hashCode()

    companion object {
        const val NO_ROUTE = -1
        private const val MAGIC = "onpath-live"

        /** The share in [text], or null when it is none or cannot be read – never throws. */
        fun parse(text: String?): LiveShare? = try {
            val f = text?.trim()?.split(' ').orEmpty()
            if (f.size < 13 || f[0] != MAGIC || f[1] != "1") null else {
                val secret = B64.decode(f[2])
                val flags = f[10].toInt()
                fun opt(s: String) = if (s == "-") ByteArray(0) else B64.decode(s)
                val name = opt(f[11])
                val head = opt(f[12])
                if (secret == null || secret.size != LiveCode.SECRET_BYTES || f[3].isEmpty() || !f[3].all(B64::isCodeChar) ||
                    name == null || head == null) null
                else LiveShare(
                    secret, f[3], f[4].toLong(), name.decodeToString(), f[5] == "1", maxOf(-1, f[6].toInt()),
                    maxOf(0, f[7].toInt()), f[8].toInt(), maxOf(0, f[9].toInt()), flags and 1 != 0, flags and 2 != 0,
                    head.takeIf { it.isNotEmpty() },
                )
            }
        } catch (e: RuntimeException) {
            null // expected: a damaged state file reads as "no share" (LiveUploader reports it)
        }
    }
}

/**
 * A stopped share's box that still has to be deleted after another share was started (review 2026-11-20: dropping it
 * left the first link showing the way up to the stop for up to 48 h). Kept as its own line beside the share.
 */
data class LiveLeftover(val boxId: String, val writeToken: String) {
    fun toText(): String = "$MAGIC $boxId $writeToken"

    companion object {
        private const val MAGIC = "onpath-live-delete"

        /** The leftover in [line], or null when it is none – never throws. */
        fun parse(line: String): LiveLeftover? {
            val f = line.trim().split(' ')
            return if (f.size >= 3 && f[0] == MAGIC && f[1].isNotEmpty() && f[2].isNotEmpty() &&
                f[1].all(B64::isCodeChar) && f[2].all(B64::isCodeChar)) LiveLeftover(f[1], f[2]) else null
        }
    }
}

/** What the app shows of the sharing. */
sealed class LiveStatus {
    /** Not sharing. */
    object Off : LiveStatus()
    /** Sharing; [lastOkMs] = the last upload that went through (null = none yet in this process). */
    data class Live(val lastOkMs: Long?) : LiveStatus()
    /** Sharing, but uploads fail since [sinceMs] ([failures] in a row) – the way is kept and goes up later. */
    data class Waiting(val sinceMs: Long, val failures: Int) : LiveStatus()
    /** The server ended the box (expired, stopped elsewhere): the sharing is over, the app says so once. */
    object StoppedByServer : LiveStatus()
}

/**
 * Uploads a shared tour (docs/design/live-tracking.md §5, §7): the state machine between the recording ([LiveTrack],
 * read-only), the server ([LiveBox]) and the remembered state ([LiveStore]).
 *
 * Pure: no thread, no clock, no Android. One caller drives it from **one** thread (the app's live lane) – it calls
 * [step] whenever something happened or [nextDueMs] is reached; [step] does at most one request and returns. Nothing
 * here throws into the caller: a port that throws is a failed step and a line in the problem log.
 *
 * The track database is the queue: the cursor says how far the server has the way; after hours without network the
 * steps catch up from there, chunk by chunk. Cursor and chunk number move only after the server's 200 – a push whose
 * answer got lost, or a process killed in between, sends the same chunk names again and they replace themselves.
 */
class LiveUploader(
    private val boxes: LiveBoxes,
    private val track: LiveTrack,
    private val store: LiveStore,
    /** n random bytes from the platform's secure random. */
    private val random: (Int) -> ByteArray,
    private val aead: Aead = AesGcm,
) {
    private var share: LiveShare? = null
    /** Boxes of stopped shares still to delete, oldest first (a share started again while its stop was on the way). */
    private var leftovers: List<LiveLeftover> = emptyList()
    private var loaded = false
    private var box: LiveBox? = null

    private var lastOkMs: Long? = null
    private var lastTryMs: Long? = null
    private var failures = 0
    private var failingSinceMs = 0L
    private var retryAfterMs: Long? = null
    private var networkBack = false
    private var event = false
    /** More is waiting right now (just claimed, or still catching up): the next step is due at once. */
    private var more = false
    private var stoppedByServer = false
    // the situation of the last head, for the pacing between two steps
    private var moving = true
    private var paused = false
    private var lowBattery = false

    /** The tour that is shared now (also while its end or its stop is still on the way to the server), or null. */
    val tourId: Long? get() = current()?.tourId

    /** True while there is anything left to do for the server – the lane keeps calling [step] until it is false. */
    val pending: Boolean get() = current() != null || leftovers.isNotEmpty()

    /** An older share's box is still to be deleted – the next steps do that first, whatever the running share's tour does. */
    val cleaning: Boolean get() { current(); return leftovers.isNotEmpty() }

    /** The link of the running share, or null. */
    val link: String? get() = current()?.takeIf { !it.stopping && !it.ending }?.let { LiveCode.link(it.secret) }

    val status: LiveStatus
        get() {
            val s = current()
            return when {
                s == null || s.stopping || s.ending -> if (stoppedByServer) LiveStatus.StoppedByServer else LiveStatus.Off
                failures > 0 -> LiveStatus.Waiting(failingSinceMs, failures)
                else -> LiveStatus.Live(lastOkMs)
            }
        }

    /**
     * Starts sharing [tourId] and returns the link to send – **no network**: the box is claimed by the next [step].
     * Sharing the tour that is shared already gives the same link (a second person). [name] = the sharer's display
     * name, may be empty.
     */
    fun start(tourId: Long, name: String): String {
        val old = current()
        if (old != null && old.tourId == tourId && !old.stopping && !old.ending) return LiveCode.link(old.secret)
        // an older share that is still stopping keeps its delete (privacy: the old link must not show the way to the stop
        // for days); one still ending is dropped – its box keeps the way the user meant to share and goes by the
        // server's own clean-up
        if (old != null && old.stopping) leftovers = leftovers + LiveLeftover(LiveKeys(old.secret).boxId, old.writeToken)
        val s = LiveShare(random(LiveCode.SECRET_BYTES), B64.encode(random(32)), tourId, name.trim().take(LiveItem.MAX_NAME))
        reset()
        stoppedByServer = false
        event = true
        put(s)
        return LiveCode.link(s.secret)
    }

    /** The tour ended: the next good steps send what is left and the last head with "ended"; then the share is forgotten. */
    fun end() {
        val s = current() ?: return
        if (s.stopping || s.ending) return
        event = true
        put(s.copy(ending = true))
    }

    /** "Stop sharing": the sign is off at once; the next steps delete the box (retried until it lands), then forget the share. */
    fun stop() {
        val s = current() ?: return
        if (s.stopping) return
        if (!s.claimed && lastTryMs == null) { forget(); return } // nothing was ever sent
        failures = 0; retryAfterMs = null; networkBack = false
        put(s.copy(stopping = true))
    }

    /** The app has told the user that the server ended the sharing. */
    fun acknowledge() { stoppedByServer = false }

    /** When the next [step] is due (ms, the caller's clock); null = nothing to do. A step before that does nothing. */
    fun nextDueMs(): Long? {
        val s = current()
        if (s == null && leftovers.isEmpty()) return null
        // the end's final head and a stop's delete do not wait for the 30-s event floor: the recording service lets its
        // wake lock go at the end, a step half a minute later may not run until the phone next wakes. A failed try
        // (back-off, the server's retry-after) still holds – that is the branch below.
        if (failures == 0 && (more || leftovers.isNotEmpty() || s!!.stopping || s.ending)) return lastTryMs?.plus(CATCH_UP_MS) ?: 0L
        return LivePolicy.nextUpload(lastOkMs, lastTryMs, moving, paused, lowBattery, event, failures, retryAfterMs, networkBack)
    }

    /**
     * Does what is due at [nowMs] – at most one request: claim the box, or one push (the route when it changed, up to
     * [CHUNKS_PER_PUSH] chunks from the cursor, the head), or the delete after [stop].
     *
     * @param event something the viewer should see soon happened (pause, resume, off-route alarm, arrived)
     * @param networkBack Android reported the network back
     */
    fun step(nowMs: Long, event: Boolean = false, networkBack: Boolean = false): LiveStatus {
        try {
            if (event) this.event = true
            if (networkBack && failures > 0) this.networkBack = true
            val due = nextDueMs() ?: return status
            if (nowMs < due) return status
            leftovers.firstOrNull()?.let { deleteLeftover(it, nowMs); return status }
            val s = current() ?: return status
            val b = box ?: guard("open the box") { boxes.open(LiveKeys(s.secret).boxId, s.writeToken) }?.also { box = it }
            if (b == null) { failed(nowMs, LiveReply(0)); return status }
            when {
                s.stopping -> delete(b, nowMs)
                !s.claimed -> claim(s, b, nowMs)
                else -> push(s, b, nowMs)
            }
        } catch (e: Exception) {
            Problems.warn("live", "Live sharing: a step failed", e)
            failed(nowMs, LiveReply(0))
        }
        return status
    }

    // ---- the three requests ----

    private fun claim(s: LiveShare, b: LiveBox, now: Long) {
        val keys = LiveKeys(s.secret)
        val r = call("claim the box") { b.claim(hash(keys.readToken), hash(s.writeToken)) }
        when {
            r.ok -> { tried(now); more = true; put(s.copy(claimed = true)) }
            r.gone -> serverEnded(r)
            else -> failed(now, r)
        }
    }

    private fun push(s: LiveShare, b: LiveBox, now: Long) {
        val keys = LiveKeys(s.secret)
        val items = ArrayList<LivePush>()
        fun seal(name: String, plain: ByteArray) { items += LivePush(name, LiveItem.seal(keys, name, plain, random(12), aead)) }

        // the route, when it is another one than the server has
        val route = guard("read the route") { track.route(s.tourId) }
        val routeVersion = when {
            route == null -> LiveShare.NO_ROUTE
            route.version == LiveShare.NO_ROUTE -> s.routeVersion // cannot be told from "none": leave what is there
            else -> route.version
        }
        if (routeVersion != s.routeVersion) seal(LiveItem.ROUTE, LiveItem.route(route?.line.orEmpty()))

        // the way since the cursor
        val max = CHUNKS_PER_PUSH * LiveChunker.MAX_POINTS
        val points = guard("read the track") { track.pointsAfter(s.tourId, s.cursorSeq, max) }.orEmpty()
            .filter { it.seq > s.cursorSeq }.sortedBy { it.seq }
        var taken = 0
        var chunk = s.nextChunk
        while (taken < points.size && chunk - s.nextChunk < CHUNKS_PER_PUSH) {
            val cut = LiveChunker.next(points.subList(taken, points.size).map { it.point }) ?: break
            seal(LiveItem.chunkName(chunk), cut.bytes)
            taken += cut.count
            chunk++
        }
        val left = taken < points.size || points.size >= max
        val cursor = if (taken > 0) points[taken - 1].seq else s.cursorSeq

        // the head; the last one ("ended") only when the whole way is up
        val ended = s.ending && !left
        val live = guard("read the status") { track.head(s.tourId) }
        val base = live ?: s.lastHead?.let(LiveItem::readHead) ?: if (s.ending) LiveHead(LiveState.ENDED) else null
        var headBytes: ByteArray? = null
        if (base != null) {
            val state = if (s.ending) LiveState.ENDED else base.state
            moving = state == LiveState.RECORDING && base.stillS == null
            paused = state == LiveState.PAUSED
            lowBattery = (base.batteryPct ?: 100) < LOW_BATTERY_PCT
            headBytes = LiveItem.head(base.copy(
                state = state, name = s.name, counter = s.counter + 1, chunks = chunk,
                nextS = LivePolicy.intervalS(moving, paused, lowBattery),
            ))
            seal(LiveItem.HEAD, headBytes)
        }

        if (items.isEmpty()) { // nothing to say yet (no status, no point): not a failure – look again at the next interval
            tried(now); lastOkMs = now; more = false; event = false
            return
        }
        val r = call("upload") { b.push(items, ended) }
        when {
            r.ok -> {
                tried(now); lastOkMs = now; event = false; more = left
                if (ended) forget()
                else put(s.copy(
                    cursorSeq = cursor, nextChunk = chunk, routeVersion = routeVersion,
                    counter = if (headBytes != null) s.counter + 1 else s.counter, lastHead = headBytes ?: s.lastHead,
                ))
            }
            r.gone -> serverEnded(r)
            else -> failed(now, r)
        }
    }

    private fun delete(b: LiveBox, now: Long) {
        val r = call("delete the box") { b.delete() }
        if (r.ok || r.gone) forget() else failed(now, r)
    }

    /** A stopped share's box from before the running one: deleted first, retried like the running share's delete. */
    private fun deleteLeftover(l: LiveLeftover, now: Long) {
        val b = guard("open an old box") { boxes.open(l.boxId, l.writeToken) }
        val r = if (b == null) LiveReply(0) else call("delete an old box") { b.delete() }
        if (r.ok || r.gone) { tried(now); leftovers = leftovers - l; save() } else failed(now, r)
    }

    // ---- bookkeeping ----

    private fun tried(now: Long) {
        lastTryMs = now; failures = 0; retryAfterMs = null; networkBack = false
    }

    private fun failed(now: Long, r: LiveReply) {
        if (failures == 0) failingSinceMs = now
        failures++
        lastTryMs = now
        networkBack = false
        retryAfterMs = if (r.busy) (r.retryAfterS ?: 0).coerceAtLeast(0) * 1000L else null
        if (r.code != 0 && !r.busy) Problems.warn("live", "Live sharing: the server answered ${r.code}")
    }

    private fun serverEnded(r: LiveReply) {
        Problems.warn("live", "Live sharing was ended by the server (${r.code})")
        val shown = share?.let { !it.stopping && !it.ending } == true
        forget()
        stoppedByServer = shown
    }

    private fun forget() {
        share = null
        reset()
        save()
    }

    private fun reset() {
        box = null
        lastOkMs = null; lastTryMs = null; failures = 0; retryAfterMs = null; networkBack = false; event = false; more = false
        moving = true; paused = false; lowBattery = false
    }

    private fun current(): LiveShare? {
        if (!loaded) {
            loaded = true
            val text = guard("read the saved state") { store.read() }
            val lines = text.orEmpty().lines().filter { it.isNotBlank() }
            // the share's line first, then one line per box still to delete (an older state is the share's line alone)
            share = lines.firstOrNull()?.let(LiveShare::parse)
            leftovers = lines.mapNotNull(LiveLeftover::parse)
            if (share == null && leftovers.isEmpty() && !text.isNullOrBlank())
                Problems.warn("live", "Live sharing: the saved state is unreadable – sharing is off")
        }
        return share
    }

    /** The memory copy is the truth for this process; the store is written through (a failed write is reported, not fatal). */
    private fun put(s: LiveShare) {
        share = s
        save()
    }

    private fun save() {
        val lines = listOfNotNull(share?.toText()) + leftovers.map { it.toText() }
        guard("save the state") { store.write(lines.joinToString("\n").ifEmpty { null }) }
    }

    private fun call(what: String, f: () -> LiveReply): LiveReply = guard(what, f) ?: LiveReply(0)

    private inline fun <T> guard(what: String, f: () -> T): T? = try {
        f()
    } catch (e: Exception) {
        Problems.warn("live", "Live sharing: could not $what", e)
        null
    }

    private fun hash(token: String) =
        Sha256.hash(token.encodeToByteArray()).joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    companion object {
        /** Most track chunks in one push (a long gap is caught up over several steps). */
        const val CHUNKS_PER_PUSH = 3
        /** While catching up, or right after the claim: the next step this soon. */
        const val CATCH_UP_MS = 1_000L
        /** Below this the phone uploads rarely (§5). */
        const val LOW_BATTERY_PCT = 15
    }
}
