package com.onpath.app.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The found place (owner 2026-11-02, replaces the dashed ring with "+" of 2026-10-31): a search hit or a place shared
 * from Google Maps, not yet part of the route, is a **route-blue pin** – a teardrop with a white outline and a white dot,
 * its tip exactly on the place. Pin = found, not in the route; a numbered circle = in the route; the blue dot = you.
 * Its name is the chip under it (ui/MapOverlays `PinChip`, a real button that opens the place's menu); holding the pin
 * opens the same menu. The thin dashed preview line from it to where it would join the route is a map line
 * ([MapController.setCandidateJoin]). Orange stays the recording's colour only.
 */
object CandidateMarker {
    /** Head radius (dp) of the pin. */
    const val HEAD_DP = 11f
    /** From the tip to the head's centre (dp). */
    const val TIP_DP = 34f
    const val OUTLINE_DP = 2f
    const val DOT_DP = 4.2f
    /** The soft shadow's offset (dp), right and down. */
    const val SHADOW_DP = 1.2f
    /** From the tip to the top of the outline (dp): menus anchored at the place go above this. */
    const val PIN_HEIGHT_DP = TIP_DP + HEAD_DP + OUTLINE_DP
    /** Bitmap size (dp): the pin, its outline and shadow; the tip [TIP_MARGIN_DP] above the bottom edge. */
    const val WIDTH_DP = 2 * (HEAD_DP + OUTLINE_DP) + 2 * SHADOW_DP + 2f
    const val TIP_MARGIN_DP = SHADOW_DP + OUTLINE_DP + 1f
    const val HEIGHT_DP = PIN_HEIGHT_DP + TIP_MARGIN_DP + 1f
    /** The preview line (map line): width and dash in line widths (MapLibre line-dasharray). */
    const val JOIN_WIDTH = 2.5f
    val JOIN_DASH = arrayOf(2f, 2f)

    /**
     * A finger at ([dxDp], [dyDp]) from the tip (screen dp, y down) holds the pin: near the place itself (26 dp, as the
     * old ring) or on the pin's head above it.
     */
    fun hit(dxDp: Float, dyDp: Float): Boolean =
        Math.hypot(dxDp.toDouble(), dyDp.toDouble()) <= 26.0 ||
            Math.hypot(dxDp.toDouble(), (dyDp + TIP_DP).toDouble()) <= HEAD_DP + 10.0

    /** The pin as a bitmap of [WIDTH_DP] × [HEIGHT_DP] at [density] (px per dp); its tip [TIP_MARGIN_DP] above the bottom. */
    fun bitmap(density: Float, densityDpi: Int, color: Int = android.graphics.Color.parseColor(MapController.ROUTE_COLOR)): Bitmap {
        val w = (WIDTH_DP * density).toInt(); val h = (HEIGHT_DP * density).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.density = densityDpi
        draw(Canvas(bmp), w / 2f, h - TIP_MARGIN_DP * density, density, color)
        return bmp
    }

    /** The teardrop outline with its tip at ([x], [y]). */
    private fun pinPath(x: Float, y: Float, density: Float): Path {
        val h = HEAD_DP * density; val l = TIP_DP * density
        return Path().apply {
            moveTo(x, y)
            cubicTo(x - 0.35f * h, y - 0.42f * l, x - h, y - 0.62f * l, x - h, y - l)
            arcTo(RectF(x - h, y - l - h, x + h, y - l + h), 180f, 180f, false)
            cubicTo(x + h, y - 0.62f * l, x + 0.35f * h, y - 0.42f * l, x, y)
            close()
        }
    }

    /** Draws the pin with its tip at ([x], [y]). */
    fun draw(c: Canvas, x: Float, y: Float, density: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // soft shadow: the same shape a little right and down
        p.style = Paint.Style.FILL
        p.color = android.graphics.Color.BLACK
        p.alpha = 0x40
        c.drawPath(pinPath(x + SHADOW_DP * density, y + SHADOW_DP * density, density), p)
        // white outline: readable on every map colour
        val pin = pinPath(x, y, density)
        p.alpha = 0xFF
        p.color = android.graphics.Color.WHITE
        p.style = Paint.Style.STROKE
        p.strokeWidth = OUTLINE_DP * 2 * density
        p.strokeJoin = Paint.Join.ROUND
        c.drawPath(pin, p)
        // the route-blue body and the white dot in the head
        p.style = Paint.Style.FILL
        p.color = color
        c.drawPath(pin, p)
        p.color = android.graphics.Color.WHITE
        c.drawCircle(x, y - TIP_DP * density, DOT_DP * density, p)
    }
}
