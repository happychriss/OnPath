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
import kotlin.math.ceil
import kotlin.math.min

/**
 * Which POI a tap or hold at a screen point means – pure, so it is unit tested. [Candidate]s are the POIs the map
 * shows (screen position, icon placed, the length of the name when the map placed it). A POI is hit when the finger's
 * box ([pick] boxPx, 24 dp) touches its icon or, for a placed name, the name beside it (left or right of the icon – the
 * style's first two anchors); the nearest POI point wins – the answer MapLibre's queryRenderedFeatures gave on the
 * icon and name layers.
 */
object PoiHit {
    class Candidate(val poi: Poi, val x: Float, val y: Float, val icon: Boolean, val nameChars: Int)

    /** Half the icon's size in dp (badge 24 px at icon-size ~1). */
    const val ICON_HALF_DP = 11f

    /** The style's POI name size (tools/make_style.py pois(): 10.5 at z14 → 12.5 at z17). */
    fun nameSizeDp(zoom: Double): Float = (10.5 + (zoom.coerceIn(14.0, 17.0) - 14.0) * (2.0 / 3.0)).toFloat()

    fun pick(cands: List<Candidate>, fx: Float, fy: Float, boxPx: Float, dpPx: Float, zoom: Double): Poi? {
        val half = boxPx / 2f
        val em = nameSizeDp(zoom) * dpPx
        val icon = ICON_HALF_DP * dpPx
        return cands.filter { c ->
            val dx = fx - c.x; val dy = fy - c.y
            val onIcon = c.icon && abs(dx) <= half + icon && abs(dy) <= half + icon
            onIcon || c.nameChars > 0 && run {
                // name box: radial offset 1.05 em from the point, at most 8 em wide (text-max-width), wrapped lines
                val w = min(8f, c.nameChars * 0.55f) * em
                val lines = ceil(c.nameChars * 0.55f / 8f).coerceAtLeast(1f)
                val h = lines * 1.2f * em
                val off = 1.05f * em
                val side = abs(dx)
                abs(dy) <= half + h / 2f && side >= off - half && side <= off + w + half
            }
        }.minByOrNull { c -> (fx - c.x) * (fx - c.x) + (fy - c.y) * (fy - c.y) }?.poi
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

        /** The map became idle: true = read now (once). */
        fun mapIdle(): Boolean = wanted.also { wanted = false }
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
    private class Entry(val poi: Poi, val icon: Boolean, val nameChars: Int)

    @Volatile private var entries: List<Entry> = emptyList()
    private val trigger = PoiHit.ReadTrigger()
    private var seq = 0

    /** The camera stopped: the next map idle reads. */
    fun cameraIdle() = trigger.cameraIdle()

    /** A new style / map data: forget the old list (it belongs to another style); the next map idle reads. */
    fun styleChanged() { entries = emptyList(); seq++; trigger.changed() }

    /** Other POI settings (kinds shown): the next map idle reads. */
    fun changed() = trigger.changed()

    /** The map became idle (render thread done, all tiles in): read what it shows when the trigger says so. */
    fun onMapIdle(map: MapLibreMap, view: View) { if (trigger.mapIdle()) read(map, view) }

    /** The POI under [x], [y] (pixels), from what the map showed at the last read; null when none. */
    fun at(map: MapLibreMap, x: Float, y: Float, boxDp: Float = 24f): Poi? = try {
        val t0 = SystemClock.uptimeMillis()
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        val list = entries
        val cands = list.map { e ->
            val s = map.projection.toScreenLocation(LatLng(e.poi.lat, e.poi.lon))
            PoiHit.Candidate(e.poi, s.x, s.y, e.icon, e.nameChars)
        }
        PoiHit.pick(cands, x, y, boxDp * dp, dp, map.cameraPosition.zoom).also {
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
            val ids = style.layers.map { it.id }.filter { it.startsWith(POI_LAYER_PREFIX) }
            val (nameIds, iconIds) = ids.partition { it.endsWith("-name") }
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
                    icons.map { Entry(it, true, named[it.id]?.name?.length ?: 0) } +
                        named.values.filter { it.id !in iconIds2 }.map { Entry(it, false, it.name?.length ?: 0) }
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
