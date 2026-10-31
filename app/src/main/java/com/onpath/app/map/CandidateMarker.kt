package com.onpath.app.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint

/**
 * The candidate marker (owner 2026-10-31, replaces the orange search ring): a place that is suggested but not yet part of
 * the route – a search hit, a place shared from Google Maps. Route blue, because adding it is a route action, but
 * DASHED and hollow like everything "not taken yet" on the map (straight legs, a re-route proposal): a dashed ring
 * around the place's own icon (never covering it), a faint blue tint inside and a small "+" badge – "can be added".
 * The thin dashed preview line from it to where it would join the route is a map line ([MapController.setSearchMark]).
 * Orange stays the recording's colour only. Its actions stay in the place card at the bottom / the hold menu.
 */
object CandidateMarker {
    /** Bitmap size (dp): the ring ([RING_DP] radius) with the badge at its upper right fits inside. */
    const val SIZE_DP = 54f
    /** Ring radius (dp) – clearly outside a POI icon (the old ring had 22). */
    const val RING_DP = 21f
    const val STROKE_DP = 3.5f
    const val HALO_DP = 7f
    const val DASH_DP = 7f
    const val GAP_DP = 4.5f
    const val BADGE_DP = 8.5f
    /** Inside tint: the route blue at ~12 % – the POI icon stays readable. */
    const val TINT_ALPHA = 0x20
    /** The preview line (map line): width and dash in line widths (MapLibre line-dasharray). */
    const val JOIN_WIDTH = 2.5f
    val JOIN_DASH = arrayOf(2f, 2f)

    /** The marker as a bitmap of [SIZE_DP] at [density] (px per dp), drawn around its centre. */
    fun bitmap(density: Float, densityDpi: Int, color: Int = android.graphics.Color.parseColor(MapController.ROUTE_COLOR)): Bitmap {
        val px = (SIZE_DP * density).toInt()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        bmp.density = densityDpi
        draw(Canvas(bmp), px / 2f, px / 2f, density, color)
        return bmp
    }

    /** Draws the marker centred at ([cx], [cy]). */
    fun draw(c: Canvas, cx: Float, cy: Float, density: Float, color: Int) {
        val r = RING_DP * density
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // faint tint inside
        p.style = Paint.Style.FILL
        p.color = color
        p.alpha = TINT_ALPHA
        c.drawCircle(cx, cy, r, p)
        // white halo under the dashes: readable on every map colour, the gaps show white
        p.style = Paint.Style.STROKE
        p.color = android.graphics.Color.WHITE
        p.alpha = 0xE6
        p.strokeWidth = HALO_DP * density
        c.drawCircle(cx, cy, r, p)
        // the dashed ring
        p.color = color
        p.alpha = 0xFF
        p.strokeWidth = STROKE_DP * density
        p.strokeCap = Paint.Cap.ROUND
        p.pathEffect = DashPathEffect(floatArrayOf(DASH_DP * density, GAP_DP * density), 0f)
        c.drawCircle(cx, cy, r, p)
        p.pathEffect = null
        // "+" badge at the upper right of the ring
        val d = (r / Math.sqrt(2.0)).toFloat()
        val bx = cx + d; val by = cy - d
        val br = BADGE_DP * density
        p.style = Paint.Style.FILL
        p.color = android.graphics.Color.WHITE
        c.drawCircle(bx, by, br + 1.5f * density, p)
        p.color = color
        c.drawCircle(bx, by, br, p)
        p.style = Paint.Style.STROKE
        p.color = android.graphics.Color.WHITE
        p.strokeWidth = 2f * density
        p.strokeCap = Paint.Cap.ROUND
        val arm = br * 0.5f
        c.drawLine(bx - arm, by, bx + arm, by, p)
        c.drawLine(bx, by - arm, bx, by + arm, p)
    }
}
