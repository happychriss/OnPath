package com.onpath.app.poi

import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.onpath.app.debug.Problems
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Which POI a tap or hold at a screen point means – pure, so it is unit tested. [Candidate]s are the POIs the map
 * shows (screen position, symbol drawn or not). A POI is hit only through its drawn symbol: the finger's box ([pick]
 * boxPx, 24 dp) touches its icon; the nearest POI point wins. A name the map drew without its symbol (an older style,
 * or any case the style check missed) is no POI – the hold is a plain map hold (owner 2026-11-15, Hilden: a bus stop's
 * bare name „Dorothea-Erxleben-Straße" read like a street name and a hold on it opened the place menu). Since
 * 2026-11-17 the style draws POI names only from z17, beside their icon in the same symbol (owner); a name is never a
 * tap target of its own – only the symbol's finger box counts.
 */
object PoiHit {
    class Candidate(val poi: Poi, val x: Float, val y: Float, val icon: Boolean)

    /** Half the icon's size in dp (badge 24 px at icon-size ~1). */
    const val ICON_HALF_DP = 11f

    /**
     * Whether the style layer [id] draws the POI symbol: every `poi-` layer – the icon bands `poi-<x>` and, from z17,
     * `poi-<x>-named` (icon + name in one symbol, tools/make_style.py pois()) – except a text-only `-name` layer (an
     * older style's naming), whose name is never a hit of its own.
     */
    fun symbolLayer(id: String): Boolean = id.startsWith(POI_LAYER_PREFIX) && !id.endsWith("-name")

    fun pick(cands: List<Candidate>, fx: Float, fy: Float, boxPx: Float, dpPx: Float): Poi? {
        val reach = boxPx / 2f + ICON_HALF_DP * dpPx
        return cands.filter { c -> c.icon && abs(fx - c.x) <= reach && abs(fy - c.y) <= reach }
            .minByOrNull { c -> (fx - c.x) * (fx - c.x) + (fy - c.y) * (fy - c.y) }?.poi
    }

    /**
     * How long a press the list has no POI for waits for a drawn frame; after that it is a plain press. A frame normally comes
     * in a few ms, so this only counts on a map that draws nothing (GitHub's slow emulator 2026-11-21: up to 770 ms).
     */
    const val PRESS_WAIT_MS = 1_000L

    /** A miss on a frame with tiles still coming is asked again, at most this often. */
    const val ASK_AGAIN_MS = 150L

    /**
     * When a waiting press asks the map (owner's Pixel 2026-11-22, 19:16 and 19:21: no idle for 30 s and more, because each
     * compass value on the position dot keeps the map repainting). Not at an idle, which may never come, but right after a
     * drawn frame: at the first one, and after a miss on a frame with tiles still coming, at the next fully drawn one or
     * [ASK_AGAIN_MS] later.
     */
    class PressAsk {
        private var missedAt = -1L

        fun wants(fully: Boolean, nowMs: Long): Boolean = missedAt < 0 || fully || nowMs - missedAt >= ASK_AGAIN_MS

        fun missedOnPartFrame(nowMs: Long) { missedAt = nowMs }
    }

    /** What a press does that came while an earlier one had no answer yet, once that one has answered. */
    enum class AfterWaiting { RUN, CLOSE_THEN_RUN, DROP }

    /**
     * Each press is played after the earlier one's answer, right away, so a burst of taps never piles up. [samePlace] +
     * not a hold over a tap: a repeat of the press that had no answer yet, dropped. A tap aimed before the menu [earlierOpened]
     * showed does not close it. A hold is real input and is never lost: that menu closes and the hold acts.
     */
    fun afterWaiting(samePlace: Boolean, olderHold: Boolean, newerHold: Boolean, earlierOpened: Boolean): AfterWaiting = when {
        samePlace && (!newerHold || olderHold) -> AfterWaiting.DROP
        earlierOpened && !newerHold -> AfterWaiting.DROP
        earlierOpened -> AfterWaiting.CLOSE_THEN_RUN
        else -> AfterWaiting.RUN
    }

    /** Two presses at one place: within the finger's reach of a symbol. */
    fun samePlace(ax: Float, ay: Float, bx: Float, by: Float, boxPx: Float, dpPx: Float): Boolean {
        val reach = boxPx / 2f + ICON_HALF_DP * dpPx
        return abs(ax - bx) <= reach && abs(ay - by) <= reach
    }

    /**
     * What a waiting press does. [decide] (POI there, plan empty) holds what the finger pressed on, so a menu that opened or
     * closed meanwhile cannot turn a hold that closes a menu into a new route point; the plan is the one shown at the answer,
     * so a plan cleared meanwhile gets the first-point menu instead of a silent point. Null = drop it, the map's mode changed.
     */
    class PressPlan<A>(private val edit: Boolean, private val decide: (poi: Boolean, planEmpty: Boolean) -> A) {
        fun action(found: Boolean, canEditNow: Boolean, planEmptyNow: Boolean): A? =
            if (canEditNow != edit) null else decide(found, planEmptyNow)
    }

    /** The field log's words for a drawn POI that was missing from the list (the E2E tests read them too). */
    const val LIST_MISSED = "A tap or hold found a drawn POI the list did not have"

    /**
     * The one press waiting for the map's next frame – it is never asked inside the press, because a query there can
     * wait for a busy render thread (1447 ms, owner's Pixel 2026-11-13). Whoever takes it finishes it, exactly once.
     */
    class PressWait<P : Any> {
        private var press: P? = null
        private var token = 0

        /** [p] waits now; returns its token and the press it replaced (to be finished first, as a plain press). */
        fun put(p: P): Pair<Int, P?> { val old = press; press = p; return ++token to old }

        fun take(): P? = press.also { press = null }

        /** The press waiting now, left waiting (the field log notes what happens to it). */
        fun peek(): P? = press

        /** The press of token [t] is still waiting. */
        fun waits(t: Int): Boolean = t == token && press != null

        /** The wait of [t] ran out: its press, unless it was already finished (or a newer one waits). */
        fun expire(t: Int): P? = if (t == token) take() else null
    }

    /**
     * Whether one repaint may be asked for. Not while one is on its way: an asked repaint counts until the next frame, or
     * [staleMs] if none comes (a stopped map), so the flag can never stick. After a camera stop also not while the camera
     * follows the position (a repaint per follow step would keep the map busy) – unless a finger moved it: a pinch keeps
     * following, and on a map that then stands still no other frame would come for the read. A press always gets its
     * one frame ([mayAskForPress]): a still follow camera draws nothing by itself.
     */
    class RepaintGate(private val staleMs: Long = 1_000) {
        private var askedAt = -1L
        private var byFinger = false

        fun mayAskForPress(nowMs: Long): Boolean {
            if (askedAt >= 0 && nowMs - askedAt < staleMs) return false
            askedAt = nowMs
            return true
        }

        /** A finger moved the camera. */
        fun fingerMoved() { byFinger = true }

        /** The camera stopped; a repaint for the read is wanted. */
        fun mayAskAfterCameraStop(nowMs: Long, following: Boolean): Boolean {
            val finger = byFinger
            byFinger = false
            return (!following || finger) && mayAskForPress(nowMs)
        }

        fun mayAskAfterStyleLoad(nowMs: Long, following: Boolean): Boolean = !following && mayAskForPress(nowMs)

        fun idleCame() { askedAt = -1L }
    }

    /**
     * When [ShownPois] reads what the map shows: once, on the first map idle (all tiles in, render thread done) after
     * the camera stopped or the map changed (style / data reload, POI settings) – never on a timer while nothing moves
     * (owner's Pixel 2026-11-13: a read every 2 s on a still map cost battery). The map's own idle events (the
     * position dot redraws it every second) read nothing by themselves.
     */
    class ReadTrigger {
        private var wanted = true

        /** The camera stopped (a pan, zoom, fly or follow step ended). */
        fun cameraIdle() { wanted = true }

        /** What the map shows changed without a camera move: a new style or map data, other POI settings. */
        fun changed() { wanted = true }

        /**
         * The map became idle: true = read now (once). [ready] = the style is fully loaded, so a read can work: an idle
         * while it is not (the new style still loading after a reload) keeps the wish for the next idle – it must not
         * use it up and leave the list empty until the next camera move (owner's Pixel 2026-11-21, 0.18.6: a tap on
         * a drawn symbol showed nothing).
         */
        fun mapIdle(ready: Boolean = true): Boolean = (wanted && ready).also { if (it) wanted = false }

        /** A read is still wanted: the list is known to be behind what the map shows. */
        val pending: Boolean get() = wanted
    }

    /** The hold ring fades in after a third of its fill (`HoldRing` in MapHost): a shorter press never showed it. */
    const val RING_SHOWS_MS = 100L

    /** What the ring did for a press: none (no ring here), hidden (finger up before it showed) or seen. */
    fun ringWord(hasRing: Boolean, fingerMs: Long): String =
        if (!hasRing) "none" else if (fingerMs < RING_SHOWS_MS) "hidden" else "seen"

    /**
     * The field log of one press, one line in Diagnostics (owner 2026-11-22: a tap on a symbol sometimes waits and a
     * second tap gets no answer). All times are ms; no place, no POI name. `wait` = the time the press waited with
     * nothing on the screen: the ring is gone by then. Pure, written once.
     */
    class PressNote(
        val no: Int, val kind: String, private val atMs: Long, val nthSinceCamera: Int, val cameraStillMs: Long,
        val fingerMs: Long, val upToAppMs: Long, val ring: String, val list: String,
    ) {
        var repaint: String? = null
        var readMs = -1L
        var queryMs = -1L
        var action: String? = null
        private var idleMs = -1L
        private var frames = 0
        private var fullFrames = 0
        private var landedMs = -1L
        private var written = false

        /** The map's first idle since the press. */
        fun sawIdle(nowMs: Long) { if (idleMs < 0) idleMs = nowMs - atMs }

        /** A drawn frame while the press waited. */
        fun sawFrame(fully: Boolean) { frames++; if (fully) fullFrames++ }

        /** A read's result reached the main thread while the press waited. */
        fun readLanded(nowMs: Long) { if (landedMs < 0) landedMs = nowMs - atMs }

        /** The press's one line, null when it was already written. */
        fun line(end: String, nowMs: Long): String? {
            if (written) return null
            written = true
            return buildString {
                append("Press $no $kind nth=$nthSinceCamera camStill=$cameraStillMs finger=$fingerMs upToApp=$upToAppMs ring=$ring $list")
                append(" | wait=${nowMs - atMs}")
                repaint?.let { append(" repaint=$it") }
                if (frames > 0) append(" frames=$frames/$fullFrames")
                if (idleMs >= 0) append(" idle=$idleMs")
                if (readMs >= 0) append(" read=$readMs")
                if (landedMs >= 0) append(" landed=$landedMs")
                if (queryMs >= 0) append(" asked=$queryMs")
                append(" | end=$end action=${action ?: "-"}")
            }
        }
    }

    /** Numbers the presses: since the app's map started, and since the camera last moved (1 = the first). */
    class PressCounter {
        private var total = 0
        private var sinceCamera = 0
        private var stoppedAt = -1L

        fun cameraMoved() { sinceCamera = 0; stoppedAt = -1L }

        fun cameraStopped(nowMs: Long) { stoppedAt = nowMs }

        fun next(nowMs: Long, kind: String, fingerMs: Long, upToAppMs: Long, ring: String, list: String) = PressNote(
            ++total, kind, nowMs, ++sinceCamera, if (stoppedAt < 0) -1L else nowMs - stoppedAt, fingerMs, upToAppMs, ring, list,
        )
    }
}

/**
 * The POIs the map shows, read from MapLibre while the map is idle ([onMapIdle], [PoiHit.ReadTrigger]), so a tap or
 * hold looks them up ([at]) without queryRenderedFeatures: that call waits for the render thread – a hold waited
 * 1447 ms for a busy frame in central London (owner's Pixel 2026-11-13); a hold must never block the main thread.
 * The read: queryRenderedFeatures has to run on the main thread (MapLibre Android), on an idle map it does not wait;
 * turning the features into POIs runs on a background thread, the list is swapped in on the main thread. Until a read
 * is done a hold uses the last list. Positions are kept as coordinates and projected at the hold (no render thread
 * involved). A press the list has no POI for waits for the map's next drawn frame ([askMapAtNextFrame]) and asks the map
 * right after it, so a stale list cannot lose a drawn symbol – also while the map never goes idle (owner's Pixel 2026-11-22).
 * Main thread only. Never throws.
 */
class ShownPois {
    private class Entry(val poi: Poi, val icon: Boolean)
    private class WaitingPress(val at: LatLng, val boxDp: Float, val hold: Boolean, val note: PoiHit.PressNote, val done: (Poi?) -> Unit) {
        val ask = PoiHit.PressAsk()
    }

    @Volatile private var entries: List<Entry> = emptyList()
    private val trigger = PoiHit.ReadTrigger()
    private var seq = 0
    private var doneSeq = 0
    private var readRunning = false
    private val repaint = PoiHit.RepaintGate()
    private val waiting = PoiHit.PressWait<WaitingPress>()
    private var readAt = 0L
    private var styleLoadsSinceRead = 0
    private var symbolLayers: Pair<Style, List<String>>? = null
    private var gone = false
    private val presses = PoiHit.PressCounter()
    private var styleLoadedAt = -1L
    private var askPosted = false

    /** A read is due or running: the list is known to be behind what the map shows. */
    private val listBehind: Boolean get() = trigger.pending || doneSeq != seq

    /**
     * The camera starts to move. A waiting press stays: it is kept as a place and projected again at the next frame.
     */
    fun cameraMoveStarted(byFinger: Boolean) {
        presses.cameraMoved()
        if (byFinger) repaint.fingerMoved()
    }

    /**
     * The camera stopped: the next map idle reads. One repaint is asked for, so that idle really comes AFTER this
     * call: when the last frame was already drawn and its idle came before the camera's idle event (a fast phone with
     * cached tiles), a still map would never idle again and the list would stay that of the previous view.
     */
    fun cameraIdle(map: MapLibreMap) {
        presses.cameraStopped(SystemClock.uptimeMillis())
        trigger.cameraIdle()
        if (repaint.mayAskAfterCameraStop(SystemClock.uptimeMillis(), following(map))) repaintNow(map, "camera stop")
    }

    /** The new style is loaded: read at the next idle – and make sure one comes on a still map. */
    fun styleLoaded(map: MapLibreMap) {
        styleLoadedAt = SystemClock.uptimeMillis()
        trigger.changed()
        if (repaint.mayAskAfterStyleLoad(SystemClock.uptimeMillis(), following(map))) repaintNow(map, "style loaded")
    }

    private fun repaintNow(map: MapLibreMap, why: String) {
        try { map.triggerRepaint() } catch (e: Exception) { Problems.warn("poi", "Repaint after $why failed", e) }
    }

    /** The camera follows the position, or that is not readable: no repaint from here. */
    private fun following(map: MapLibreMap): Boolean = try {
        val c = map.locationComponent
        c.isLocationComponentActivated && c.cameraMode != CameraMode.NONE
    } catch (e: Exception) {
        Problems.warn("poi", "Camera mode not readable, no repaint", e)
        true
    }

    private fun styleReady(map: MapLibreMap): Boolean =
        try { map.style?.isFullyLoaded == true } catch (e: Exception) { Problems.warn("poi", "Style state not readable", e); false }

    /** A new style / map data: forget the old list (it belongs to another style); the next map idle reads. */
    fun styleChanged() {
        finishWaitingPress("newStyle")
        entries = emptyList(); symbolLayers = null; seq++; readRunning = false; styleLoadsSinceRead++; trigger.changed()
    }

    /** Other POI settings (kinds shown): the next map idle reads. */
    fun changed() = trigger.changed()

    /** The map became idle (render thread done, all tiles in): a read that is due runs. */
    fun onMapIdle(map: MapLibreMap, view: View) {
        repaint.idleCame()
        waiting.peek()?.note?.sawIdle(SystemClock.uptimeMillis())
        if (styleReady(map) && trigger.mapIdle(true)) read(map, view)
    }

    /**
     * A frame was drawn: a waiting press asks the map right after it, outside this callback. Every frame comes here, so
     * without a waiting press this does nothing more than clear the repaint gate.
     */
    fun onMapFrame(map: MapLibreMap, view: View, fully: Boolean) {
        repaint.idleCame()
        val p = waiting.peek() ?: return
        p.note.sawFrame(fully)
        if (askPosted || !p.ask.wants(fully, SystemClock.uptimeMillis())) return
        askPosted = true
        view.post {
            askPosted = false
            if (!gone) resolveWaitingPress(map, view, last = fully)
        }
    }

    /**
     * A press came while [waiting] has one without an answer: that one answers now, then this one is played on what it
     * did ([PoiHit.afterWaiting]) – all in one step after this touch, so a burst of taps never queues up. False = nothing
     * waits, the caller goes on at once.
     */
    fun pressWhileOneWaits(map: MapLibreMap, view: View, ll: LatLng, hold: Boolean, note: PoiHit.PressNote, boxDp: Float = 24f,
                           menuShown: () -> Boolean, closeMenu: () -> Unit, run: () -> Unit): Boolean {
        val older = waiting.peek() ?: return false
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        val a = screenOf(map, older)
        val b = try { map.projection.toScreenLocation(ll) } catch (e: Exception) {
            Problems.warn("poi", "A second press could not be placed on the screen, treated as another place", e); null
        }
        val same = a != null && b != null && PoiHit.samePlace(a.x, a.y, b.x, b.y, boxDp * dp, dp)
        val olderHold = older.hold
        val shownBefore = menuShown()
        view.post {
            if (gone) { pressEnded(note, "mapGone"); return@post }
            resolveWaitingPress(map, view, last = true)
            when (PoiHit.afterWaiting(same, olderHold, hold, !shownBefore && menuShown())) {
                PoiHit.AfterWaiting.DROP -> pressEnded(note, if (same) "repeatOfWaiting" else "menuJustOpened", "dropped")
                PoiHit.AfterWaiting.CLOSE_THEN_RUN -> { closeMenu(); run() }
                PoiHit.AfterWaiting.RUN -> run()
            }
        }
        return true
    }

    /** The POI under [x], [y] (pixels), from what the map showed at the last read; null when none. */
    fun at(map: MapLibreMap, x: Float, y: Float, boxDp: Float = 24f): Poi? = try {
        val t0 = SystemClock.uptimeMillis()
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        val list = entries
        val cands = list.map { e ->
            val s = map.projection.toScreenLocation(LatLng(e.poi.lat, e.poi.lon))
            PoiHit.Candidate(e.poi, s.x, s.y, e.icon)
        }
        PoiHit.pick(cands, x, y, boxDp * dp, dp).also {
            val ms = SystemClock.uptimeMillis() - t0
            if (ms > 50) Problems.trace("poi", "POI lookup at the finger took $ms ms (${list.size} shown POIs)")
        }
    } catch (e: Exception) {
        Problems.warn("poi", "POI lookup at the finger failed, treated as no POI", e)
        null
    }

    /**
     * A tap / hold [at] found nothing for: the press waits for the map's next drawn frame and [done] gets the POI drawn at the
     * place [ll], or null (a plain press) if none is drawn, the style changes or [pressTimedOut] comes. Never asks the map
     * here; it asks for the one frame. Returns the wait's token, or null when it cannot wait (no style yet): the caller
     * goes on at once.
     */
    fun askMapAtNextFrame(map: MapLibreMap, ll: LatLng, hold: Boolean, note: PoiHit.PressNote, boxDp: Float = 24f, done: (Poi?) -> Unit): Int? {
        if (!styleReady(map)) return null
        val (token, older) = waiting.put(WaitingPress(ll, boxDp, hold, note, done))
        older?.let { finish(it, null, "newerPress") }
        val ask = repaint.mayAskForPress(SystemClock.uptimeMillis())
        note.repaint = if (ask) "asked" else "onItsWay"
        if (ask) repaintNow(map, "list miss")
        return token
    }

    /** The field log's note for a press that starts now: what the list knows at this moment. */
    fun pressNote(map: MapLibreMap, kind: String, fingerMs: Long, upToAppMs: Long, ring: String): PoiHit.PressNote {
        val ageMs = if (readAt == 0L) -1L else SystemClock.uptimeMillis() - readAt
        val list = "list=${entries.size} age=$ageMs due=${trigger.pending} reading=$readRunning " +
            "styleOk=${styleReady(map)} styleLoads=$styleLoadsSinceRead"
        return presses.next(SystemClock.uptimeMillis(), kind, fingerMs, upToAppMs, ring, list)
    }

    /** The press is over: its one line goes into the field log. */
    fun pressEnded(note: PoiHit.PressNote, end: String, action: String? = note.action) {
        note.action = action
        note.line(end, SystemClock.uptimeMillis())?.let { Problems.trace("poi", it) }
    }

    /** The wait of [token] ran out, no usable frame came: the press goes on as a plain press. */
    fun pressTimedOut(token: Int) {
        waiting.expire(token)?.let {
            Problems.trace("poi", "No map frame in time for a press (${listInfo()}), plain press")
            finish(it, null, "timeout")
        }
    }

    /** A press still waiting goes on as a plain press (a new style). */
    fun finishWaitingPress(why: String) { waiting.take()?.let { finish(it, null, why) } }

    /** The map host goes away: a waiting press is dropped, nothing is left to act on. */
    fun dispose() { gone = true; waiting.take()?.let { pressEnded(it.note, "mapGone") } }

    private fun finish(p: WaitingPress, poi: Poi?, how: String) {
        try { p.done(poi) } catch (e: Exception) { Problems.error("poi", "A press on the map failed after waiting for the map", e) }
        pressEnded(p.note, how)
    }

    private fun listInfo(): String {
        val ageS = if (readAt == 0L) -1L else (SystemClock.uptimeMillis() - readAt) / 1000
        return "${entries.size} listed POIs, last read ${if (ageS < 0) "never" else "$ageS s ago"}, style loads since: $styleLoadsSinceRead"
    }

    private fun screenOf(map: MapLibreMap, p: WaitingPress): android.graphics.PointF? = try {
        map.projection.toScreenLocation(p.at)
    } catch (e: Exception) {
        Problems.warn("poi", "A waiting press could not be placed on the screen, plain press", e)
        null
    }

    /**
     * After a drawn frame or for a newer press, never inside a press: the list again, else one small query at the finger. A
     * miss on a frame with tiles still coming keeps waiting, unless this is the [last] chance.
     */
    private fun resolveWaitingPress(map: MapLibreMap, view: View, last: Boolean) {
        val p = waiting.peek() ?: return
        if (!styleReady(map)) {
            if (last) { waiting.take(); finish(p, null, "styleNotReady") }
            return
        }
        val s = screenOf(map, p)
        if (s == null) { waiting.take(); return finish(p, null, "noScreen") }
        val listed = at(map, s.x, s.y, p.boxDp)
        val found = listed ?: queryAtFinger(map, view, p, s.x, s.y, last)
        if (found == null && !last) { p.ask.missedOnPartFrame(SystemClock.uptimeMillis()); return }
        waiting.take()
        finish(p, found, if (listed != null) "listAtFrame" else if (found != null) "mapAtFrame" else "nothingThere")
    }

    /** The symbol layers' ids of [style], kept per style (every read refreshes them). */
    private fun symbolLayerIds(style: Style): List<String> =
        symbolLayers?.takeIf { it.first === style }?.second
            ?: style.layers.map { it.id }.filter { PoiHit.symbolLayer(it) }.also { symbolLayers = style to it }

    private fun queryAtFinger(map: MapLibreMap, view: View, p: WaitingPress, x: Float, y: Float, last: Boolean): Poi? {
        val behind = listBehind // a read is still due or running: a miss is expected
        val info = listInfo()
        return try {
            val t0 = SystemClock.uptimeMillis()
            val dp = android.content.res.Resources.getSystem().displayMetrics.density
            val style = map.style
            if (style == null || !style.isFullyLoaded) return null
            val ids = symbolLayerIds(style)
            if (ids.isEmpty()) return null
            val reach = p.boxDp * dp / 2f + PoiHit.ICON_HALF_DP * dp
            val feats = map.queryRenderedFeatures(RectF(x - reach, y - reach, x + reach, y + reach), *ids.toTypedArray())
            val ms = SystemClock.uptimeMillis() - t0
            p.note.queryMs = ms
            if (ms > 100) Problems.trace("poi", "POI at the finger asked in $ms ms on the main thread (after a frame)")
            val cands = feats.mapNotNull { Poi.fromFeature(it) }.distinctBy { it.id }.map { c ->
                val s = map.projection.toScreenLocation(LatLng(c.lat, c.lon))
                PoiHit.Candidate(c, s.x, s.y, true)
            }
            val hit = PoiHit.pick(cands, x, y, p.boxDp * dp, dp)
            if (hit == null) { if (last) Problems.trace("poi", "No POI at the finger, in the list or on the map ($info)") }
            else {
                val line = "${PoiHit.LIST_MISSED} ($info)"
                if (behind) Problems.trace("poi", "$line – its read was still due")
                else Problems.warn("poi", "$line – the list was stale, read again")
                trigger.changed()
                view.post { if (!gone && styleReady(map) && trigger.mapIdle(true)) read(map, view) } // not in this slice
            }
            hit
        } catch (e: Exception) {
            Problems.warn("poi", "POI query at the finger failed, treated as no POI", e)
            null
        }
    }

    /** True = the read runs. */
    private fun read(map: MapLibreMap, view: View): Boolean {
        try {
            val t0 = SystemClock.uptimeMillis()
            val style = map.style ?: return false
            if (!style.isFullyLoaded) return false
            // symbol layers by their id (PoiHit.symbolLayer): reading a layer's icon image at runtime said "no icon" for
            // every POI layer on the owner's Pixel (2026-11-16 23:35, no POI hit any more) – the id rule is the style's own
            val (iconIds, nameIds) = style.layers.map { it.id }.filter { it.startsWith(POI_LAYER_PREFIX) }
                .partition { PoiHit.symbolLayer(it) }
            symbolLayers = style to iconIds
            val box = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
            fun query(layers: List<String>): List<Feature> =
                if (layers.isEmpty()) emptyList() else map.queryRenderedFeatures(box, *layers.toTypedArray())
            // main thread (MapLibre's rule) – only the two queries
            val iconFeatures = query(iconIds)
            val nameFeatures = query(nameIds)
            val ms = SystemClock.uptimeMillis() - t0
            if (ms > 100) Problems.trace("poi", "Shown POIs read in $ms ms on the main thread (map idle)")
            waiting.peek()?.note?.readMs = ms
            val mine = ++seq
            readRunning = true
            worker.execute {
                val list = try {
                    val icons = iconFeatures.mapNotNull { Poi.fromFeature(it) }.distinctBy { it.id }
                    val named = nameFeatures.mapNotNull { Poi.fromFeature(it) }.associateBy { it.id }
                    val iconIds2 = icons.map { it.id }.toSet()
                    icons.map { Entry(it, true) } + named.values.filter { it.id !in iconIds2 }.map { Entry(it, false) }
                } catch (e: Exception) {
                    Problems.warn("poi", "Shown POIs not read, a tap or hold uses the last list", e); null
                }
                view.post {
                    if (mine != seq || gone) return@post // a newer read or style answers the press
                    readRunning = false
                    waiting.peek()?.note?.readLanded(SystemClock.uptimeMillis())
                    if (list == null) return@post
                    entries = list; doneSeq = mine; readAt = SystemClock.uptimeMillis(); styleLoadsSinceRead = 0
                    if (styleLoadedAt >= 0) {
                        Problems.trace("poi", "First POI list after the style load: ${list.size} POIs, ${readAt - styleLoadedAt} ms")
                        styleLoadedAt = -1L
                    }
                }
            }
            return true
        } catch (e: Exception) {
            readRunning = false
            Problems.warn("poi", "Shown POIs not read (map idle), a tap or hold uses the last list", e)
            return false
        }
    }

    companion object {
        /** One background thread for all maps: turning the read features into POIs. */
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "shown-pois").apply { isDaemon = true } }
    }
}
