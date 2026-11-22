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
     * How long a press the list has no POI for waits for the map; after that it is a plain press. The idle normally comes
     * in ~10 ms, so this only counts on a busy map or a slow phone – where a shorter bound turned a hold on a drawn symbol
     * into a route point (GitHub's emulator 2026-11-21: the idle came after 160–650 ms).
     */
    const val PRESS_WAIT_MS = 1_000L

    /** A read has asked the map and only its result is on the way: the press waits for it this much longer, at most. */
    const val READ_LAND_MS = 1_000L
    const val READ_STEP_MS = 40L

    /**
     * The press's time ran out while a read is under way: it waits for that read, because a plain press now would turn a
     * hold on a drawn symbol into a route point (GitHub's slow emulator 2026-11-21: read done, timer 3 ms before it landed).
     */
    fun keepWaitingForRead(readRunning: Boolean, waitedMs: Long): Boolean = readRunning && waitedMs < READ_LAND_MS

    /**
     * What a waiting press does, decided when the finger pressed ([plain] without a POI there, [onPoi] with one): what
     * opened or closed meanwhile cannot turn a hold that closes a menu into a new route point. Null = drop it, the map's
     * mode changed while it waited.
     */
    class PressPlan<A>(private val edit: Boolean, val plain: A, val onPoi: A) {
        fun action(found: Boolean, canEditNow: Boolean): A? = if (canEditNow != edit) null else if (found) onPoi else plain
    }

    /** The field log's words for a drawn POI that was missing from the list (the E2E tests read them too). */
    const val LIST_MISSED = "A tap or hold found a drawn POI the list did not have"

    /**
     * The one press waiting for the map's next idle – it is never asked inside the press, because a query there can
     * wait for a busy render thread (1447 ms, owner's Pixel 2026-11-13). Whoever takes it finishes it, exactly once.
     */
    class PressWait<P : Any> {
        private var press: P? = null
        private var token = 0

        /** [p] waits now; returns its token and the press it replaced (to be finished first, as a plain press). */
        fun put(p: P): Pair<Int, P?> { val old = press; press = p; return ++token to old }

        fun take(): P? = press.also { press = null }

        /** The press of token [t] is still waiting. */
        fun waits(t: Int): Boolean = t == token && press != null

        /** The wait of [t] ran out: its press, unless it was already finished (or a newer one waits). */
        fun expire(t: Int): P? = if (t == token) take() else null
    }

    /**
     * Whether one repaint may be asked for. Not while one is on its way: an asked repaint counts until the next idle, or
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
}

/**
 * The POIs the map shows, read from MapLibre while the map is idle ([onMapIdle], [PoiHit.ReadTrigger]), so a tap or
 * hold looks them up ([at]) without queryRenderedFeatures: that call waits for the render thread – a hold waited
 * 1447 ms for a busy frame in central London (owner's Pixel 2026-11-13); a hold must never block the main thread.
 * The read: queryRenderedFeatures has to run on the main thread (MapLibre Android), on an idle map it does not wait;
 * turning the features into POIs runs on a background thread, the list is swapped in on the main thread. Until a read
 * is done a hold uses the last list. Positions are kept as coordinates and projected at the hold (no render thread
 * involved). A press the list has no POI for waits for the map's next idle ([askMapAtNextIdle]) and asks the map there,
 * so a stale list cannot lose a drawn symbol (owner's Pixel 2026-11-21, cause unproven). Main thread only. Never throws.
 */
class ShownPois {
    private class Entry(val poi: Poi, val icon: Boolean)
    private class WaitingPress(val at: LatLng, val boxDp: Float, val done: (Poi?) -> Unit)

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

    /** A read is due or running: the list is known to be behind what the map shows. */
    private val listBehind: Boolean get() = trigger.pending || doneSeq != seq

    /**
     * The camera starts to move. A finger on it ends a waiting press (a plain press now); a follow step does not – the
     * press is kept as a place and projected again at the idle.
     */
    fun cameraMoveStarted(byFinger: Boolean) {
        if (byFinger) { repaint.fingerMoved(); finishWaitingPress() }
    }

    /**
     * The camera stopped: the next map idle reads. One repaint is asked for, so that idle really comes AFTER this
     * call: when the last frame was already drawn and its idle came before the camera's idle event (a fast phone with
     * cached tiles), a still map would never idle again and the list would stay that of the previous view.
     */
    fun cameraIdle(map: MapLibreMap) {
        trigger.cameraIdle()
        if (repaint.mayAskAfterCameraStop(SystemClock.uptimeMillis(), following(map))) repaintNow(map, "camera stop")
    }

    /** The new style is loaded: read at the next idle – and make sure one comes on a still map. */
    fun styleLoaded(map: MapLibreMap) {
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
        finishWaitingPress()
        entries = emptyList(); symbolLayers = null; seq++; readRunning = false; styleLoadsSinceRead++; trigger.changed()
    }

    /** Other POI settings (kinds shown): the next map idle reads. */
    fun changed() = trigger.changed()

    /**
     * The map became idle (render thread done, all tiles in). A read that is due runs and a waiting press is answered
     * from it; with none due the press asks the map here, once.
     */
    fun onMapIdle(map: MapLibreMap, view: View) {
        repaint.idleCame()
        if (!styleReady(map)) { finishWaitingPress(); return }
        val reading = trigger.mapIdle(true) && read(map, view)
        if (!reading) resolveWaitingPress(map, view)
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
     * A tap / hold [at] found nothing for: the press waits for the map's next idle and [done] gets the POI drawn at the
     * place [ll], or null (a plain press) if none is drawn, a finger moves the camera, the style changes or
     * [pressTimedOut] comes. Never asks the map here; it asks for the one frame that brings the idle. Returns the wait's
     * token, or null when it cannot wait (no style yet): the caller goes on at once.
     */
    fun askMapAtNextIdle(map: MapLibreMap, ll: LatLng, boxDp: Float = 24f, done: (Poi?) -> Unit): Int? {
        if (!styleReady(map)) return null
        val (token, older) = waiting.put(WaitingPress(ll, boxDp, done))
        older?.let { finish(it, null) }
        if (repaint.mayAskForPress(SystemClock.uptimeMillis())) repaintNow(map, "list miss")
        return token
    }

    /**
     * The wait of [token] ran out (a busy map): the press goes on as a plain press – unless a read is under way, then it
     * waits for that read's result, bounded ([PoiHit.keepWaitingForRead]). Returns the ms after which the caller asks
     * again with [waitedMs] grown by them, or null when the press is finished.
     */
    fun pressTimedOut(token: Int, waitedMs: Long = 0): Long? {
        if (waiting.waits(token) && PoiHit.keepWaitingForRead(readRunning, waitedMs)) return PoiHit.READ_STEP_MS
        waiting.expire(token)?.let {
            Problems.trace("poi", "No map idle in time for a press (${listInfo()}), plain press")
            finish(it, null)
        }
        return null
    }

    /** A press still waiting goes on as a plain press (a newer press, a finger on the camera, a new style). */
    fun finishWaitingPress() { waiting.take()?.let { finish(it, null) } }

    /** The map host goes away: a waiting press is dropped, nothing is left to act on. */
    fun dispose() { gone = true; waiting.take() }

    private fun finish(p: WaitingPress, poi: Poi?) {
        try { p.done(poi) } catch (e: Exception) { Problems.error("poi", "A press on the map failed after waiting for the map", e) }
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

    /** On the map's idle only ([onMapIdle]): the list again (a read may have landed), else one small query at the finger. */
    private fun resolveWaitingPress(map: MapLibreMap, view: View) {
        val p = waiting.take() ?: return
        val s = screenOf(map, p) ?: return finish(p, null)
        finish(p, at(map, s.x, s.y, p.boxDp) ?: queryAtFinger(map, view, p, s.x, s.y))
    }

    /** The read a waiting press was waiting for has landed: it answers from the new list. */
    private fun answerFromLandedRead(map: MapLibreMap) {
        val p = waiting.take() ?: return
        val s = screenOf(map, p) ?: return finish(p, null)
        val hit = at(map, s.x, s.y, p.boxDp)
        if (hit != null) Problems.trace("poi", "${PoiHit.LIST_MISSED} (${listInfo()}) – its read was still due")
        finish(p, hit)
    }

    /** The symbol layers' ids of [style], kept per style (every read refreshes them). */
    private fun symbolLayerIds(style: Style): List<String> =
        symbolLayers?.takeIf { it.first === style }?.second
            ?: style.layers.map { it.id }.filter { PoiHit.symbolLayer(it) }.also { symbolLayers = style to it }

    private fun queryAtFinger(map: MapLibreMap, view: View, p: WaitingPress, x: Float, y: Float): Poi? {
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
            if (ms > 100) Problems.trace("poi", "POI at the finger asked in $ms ms on the main thread (map idle)")
            val cands = feats.mapNotNull { Poi.fromFeature(it) }.distinctBy { it.id }.map { c ->
                val s = map.projection.toScreenLocation(LatLng(c.lat, c.lon))
                PoiHit.Candidate(c, s.x, s.y, true)
            }
            val hit = PoiHit.pick(cands, x, y, p.boxDp * dp, dp)
            if (hit == null) Problems.trace("poi", "No POI at the finger, in the list or on the map ($info)")
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

    /** True = the read runs and its landing answers a waiting press. */
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
                    if (list == null) { finishWaitingPress(); return@post }
                    entries = list; doneSeq = mine; readAt = SystemClock.uptimeMillis(); styleLoadsSinceRead = 0
                    // a newer read is wanted (the camera stopped since this one began): this list is of the old view, a
                    // miss in it says nothing – the press waits for that read, bounded by its timeout
                    if (!trigger.pending) answerFromLandedRead(map)
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
