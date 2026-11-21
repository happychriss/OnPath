package com.onpath.app.poi

import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.onpath.app.debug.Problems
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
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
    }
}

/**
 * The POIs the map shows, read from MapLibre while the map is idle ([onMapIdle], [PoiHit.ReadTrigger]), so a tap or
 * hold looks them up ([at]) without queryRenderedFeatures: that call waits for the render thread – a hold waited
 * 1447 ms for a busy frame in central London (owner's Pixel 2026-11-13); a hold must never block the main thread.
 * The read: queryRenderedFeatures has to run on the main thread (MapLibre Android), on an idle map it does not wait;
 * turning the features into POIs runs on a background thread, the list is swapped in on the main thread. Until a read
 * is done a hold uses the last list. Positions are kept as coordinates and projected at the hold (no render thread
 * involved). Main thread only. Never throws.
 */
class ShownPois {
    private class Entry(val poi: Poi, val icon: Boolean)

    @Volatile private var entries: List<Entry> = emptyList()
    private val trigger = PoiHit.ReadTrigger()
    private var seq = 0

    /**
     * The camera stopped: the next map idle reads. One repaint is asked for, so that idle really comes AFTER this
     * call: when the last frame was already drawn and its idle came before the camera's idle event (a fast phone with
     * cached tiles), a still map would never idle again and the list would stay that of the previous view.
     */
    fun cameraIdle(map: MapLibreMap) { trigger.cameraIdle(); repaintOnce(map, "camera stop") }

    /** The new style is loaded: read at the next idle – and make sure one comes on a still map. */
    fun styleLoaded(map: MapLibreMap) { trigger.changed(); repaintOnce(map, "style loaded") }

    private fun repaintOnce(map: MapLibreMap, why: String) {
        try { map.triggerRepaint() } catch (e: Exception) { Problems.warn("poi", "Repaint after $why failed", e) }
    }

    /** A new style / map data: forget the old list (it belongs to another style); the next map idle reads. */
    fun styleChanged() { entries = emptyList(); seq++; trigger.changed() }

    /** Other POI settings (kinds shown): the next map idle reads. */
    fun changed() = trigger.changed()

    /** The map became idle (render thread done, all tiles in): read what it shows when the trigger says so. */
    fun onMapIdle(map: MapLibreMap, view: View) {
        val ready = try { map.style?.isFullyLoaded == true } catch (e: Exception) { Problems.warn("poi", "Style state not readable", e); false }
        if (trigger.mapIdle(ready)) read(map, view)
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

    private fun read(map: MapLibreMap, view: View) {
        try {
            val t0 = SystemClock.uptimeMillis()
            val style = map.style ?: return
            if (!style.isFullyLoaded) return
            // symbol layers by their id (PoiHit.symbolLayer): reading a layer's icon image at runtime said "no icon" for
            // every POI layer on the owner's Pixel (2026-11-16 23:35, no POI hit any more) – the id rule is the style's own
            val (iconIds, nameIds) = style.layers.map { it.id }.filter { it.startsWith(POI_LAYER_PREFIX) }
                .partition { PoiHit.symbolLayer(it) }
            val box = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
            fun query(layers: List<String>): List<Feature> =
                if (layers.isEmpty()) emptyList() else map.queryRenderedFeatures(box, *layers.toTypedArray())
            // main thread (MapLibre's rule) – only the two queries
            val iconFeatures = query(iconIds)
            val nameFeatures = query(nameIds)
            val ms = SystemClock.uptimeMillis() - t0
            if (ms > 100) Problems.trace("poi", "Shown POIs read in $ms ms on the main thread (map idle)")
            val mine = ++seq
            worker.execute {
                val list = try {
                    val icons = iconFeatures.mapNotNull { Poi.fromFeature(it) }.distinctBy { it.id }
                    val named = nameFeatures.mapNotNull { Poi.fromFeature(it) }.associateBy { it.id }
                    val iconIds2 = icons.map { it.id }.toSet()
                    icons.map { Entry(it, true) } + named.values.filter { it.id !in iconIds2 }.map { Entry(it, false) }
                } catch (e: Exception) {
                    Problems.warn("poi", "Shown POIs not read, a tap or hold uses the last list", e); null
                }
                if (list != null) view.post { if (mine == seq) entries = list }
            }
        } catch (e: Exception) {
            Problems.warn("poi", "Shown POIs not read (map idle), a tap or hold uses the last list", e)
        }
    }

    companion object {
        /** One background thread for all maps: turning the read features into POIs. */
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "shown-pois").apply { isDaemon = true } }
    }
}
