package com.onpath.app.poi

import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.onpath.app.debug.Problems
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
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

    /** Read what the map shows again at most this long after the last read when it did not move. */
    const val MAX_AGE_MS = 2_000L

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

    /** Zoom, turn and tilt of the map at a read. */
    data class Cam(val zoom: Double, val bearing: Double, val tilt: Double)

    /**
     * The map moved enough since the last read that what it shows must be read again at once (else at most every
     * [MAX_AGE_MS]): zoom, turn or tilt changed, or the old centre is now [shiftPx] away from the centre.
     */
    fun moved(a: Cam?, b: Cam, shiftPx: Float, screenPx: Float): Boolean = a == null || abs(a.zoom - b.zoom) > 0.05 ||
        abs(((a.bearing - b.bearing + 540.0) % 360.0) - 180.0) > 2.0 || abs(a.tilt - b.tilt) > 1.0 || shiftPx > 0.1f * screenPx
}

/**
 * The POIs the map shows, read from MapLibre while the map is idle ([onIdle]), so a tap or hold looks them up
 * ([at]) without queryRenderedFeatures on the main thread. That call waits for the render thread: in central London a
 * hold waited 1447 ms for a busy frame (owner's Pixel 2026-11-13) – a hold must never block the main thread.
 * Positions are kept as coordinates and projected at the hold (no render thread involved), so a POI under the finger is
 * found after the map moved a bit, too; what the map shows is read again on the next idle (at once after a real move,
 * else every [PoiHit.MAX_AGE_MS] – a changed POI setting counts within that). Main thread only. Never throws.
 */
class ShownPois {
    private class Entry(val poi: Poi, val icon: Boolean, val nameChars: Int)

    private var entries: List<Entry> = emptyList()
    private var cam: PoiHit.Cam? = null
    private var readCentre: LatLng? = null
    private var readAt = 0L
    private var idle = false
    private var pending = false

    /** The map started moving: a delayed read waits for the next idle. */
    fun moving() { idle = false }

    /** Forget everything (a new style): until the next idle no POI is found. */
    fun clear() { entries = emptyList(); cam = null; readCentre = null; readAt = 0L; idle = false }

    /** The map became idle (render thread done): read what it shows when it moved or the last read is old. */
    fun onIdle(map: MapLibreMap, view: View) {
        idle = true
        val age = SystemClock.uptimeMillis() - readAt
        val moved = try {
            val p = map.cameraPosition
            val shift = readCentre?.let { c ->
                val s = map.projection.toScreenLocation(c)
                Math.hypot((s.x - view.width / 2f).toDouble(), (s.y - view.height / 2f).toDouble()).toFloat()
            } ?: 0f
            PoiHit.moved(cam, PoiHit.Cam(p.zoom, p.bearing, p.tilt), shift, maxOf(view.width, view.height).toFloat())
        } catch (e: Exception) {
            Problems.warn("poi", "Map camera not readable, shown POIs read again", e); true
        }
        if (moved || age >= PoiHit.MAX_AGE_MS) read(map, view)
        else if (!pending) {
            // once more when the last read gets old, if the map is still idle then (a POI setting changed, tiles came in)
            pending = true
            view.postDelayed({ pending = false; if (idle) read(map, view) }, PoiHit.MAX_AGE_MS - age)
        }
    }

    /** The POI under [x], [y] (pixels), from what the map showed at its last idle; null when none. */
    fun at(map: MapLibreMap, x: Float, y: Float, boxDp: Float = 24f): Poi? = try {
        val t0 = SystemClock.uptimeMillis()
        val dp = android.content.res.Resources.getSystem().displayMetrics.density
        val cands = entries.map { e ->
            val s = map.projection.toScreenLocation(LatLng(e.poi.lat, e.poi.lon))
            PoiHit.Candidate(e.poi, s.x, s.y, e.icon, e.nameChars)
        }
        PoiHit.pick(cands, x, y, boxDp * dp, dp, map.cameraPosition.zoom).also {
            val ms = SystemClock.uptimeMillis() - t0
            if (ms > 50) Problems.trace("poi", "POI lookup at the finger took $ms ms (${entries.size} shown POIs)")
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
            fun query(layers: List<String>): List<Poi> = if (layers.isEmpty()) emptyList()
                else map.queryRenderedFeatures(box, *layers.toTypedArray()).mapNotNull { Poi.fromFeature(it) }.distinctBy { it.id }
            val icons = query(iconIds)
            val named = query(nameIds).associateBy { it.id }
            val iconIdSet = icons.map { it.id }.toSet()
            entries = icons.map { Entry(it, true, named[it.id]?.name?.length ?: 0) } +
                named.values.filter { it.id !in iconIdSet }.map { Entry(it, false, it.name?.length ?: 0) }
            // the camera this read belongs to: [onIdle] measures the next move from it
            val p = map.cameraPosition
            cam = PoiHit.Cam(p.zoom, p.bearing, p.tilt)
            readCentre = p.target
            readAt = SystemClock.uptimeMillis()
            val ms = readAt - t0
            if (ms > 50) Problems.trace("poi", "Shown POIs read in $ms ms (${entries.size}, map idle)")
        } catch (e: Exception) {
            Problems.warn("poi", "Shown POIs not read (map idle), a tap or hold may miss a POI until the next read", e)
        }
    }
}
