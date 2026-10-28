package com.onpath.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.onpath.app.map.MapController

/**
 * The small blue arrow at the position dot showing where you face (owner 2026-10-20: "turn the phone – the arrow
 * turns"), drawn by the app in heading-up following, where MapLibre's own arrow would show the route direction.
 * Placed at the dot's fixed screen point and turned by (view direction − map bearing). Reads its state only in the
 * draw phase: no recomposition per frame.
 */
@Composable
fun FacingArrow(f: MapController.Facing, mapBearing: () -> Float) {
    Canvas(Modifier.fillMaxSize()) {
        if (!f.visible) return@Canvas
        val c = Offset(f.x, f.y)
        // just outside the dot and its white ring (the dot is 1.3× since 0.11.7); the arrow larger too (owner 2026-10-28)
        val r = 16.dp.toPx()
        val w = 9.dp.toPx(); val h = 11.dp.toPx()
        rotate(f.viewHeading - mapBearing(), pivot = c) {
            val tip = Offset(c.x, c.y - r - h)
            val path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(c.x - w, c.y - r)
                lineTo(c.x + w, c.y - r)
                close()
            }
            drawPath(path, OnPathColors.Position)
        }
    }
}

