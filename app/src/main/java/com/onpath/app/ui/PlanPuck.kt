package com.onpath.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.onpath.app.routing.Activity

/**
 * The position sign while the route is changed during a tour (owner 2026-11-01: "das Symbol, was ich gerade mache –
 * Fahrrad, Wandern"): the activity's icon in green in a white circle with a green ring, no cone and no arrow – at a
 * glance not the blue Navigate dot ([com.onpath.app.map.MapController.setPlanPuck]).
 */
@Composable
fun rememberPlanPuck(activity: Activity): android.graphics.Bitmap {
    val painter = rememberVectorPainter(activity.icon)
    val density = LocalDensity.current
    val green = OnPathColors.Green
    return remember(activity, density) {
        val side = with(density) { SIZE.toPx() }
        val ring = with(density) { RING.toPx() }
        val icon = with(density) { ICON.toPx() }
        val px = side.toInt()
        val img = ImageBitmap(px, px)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(side, side)) {
            val c = Offset(side / 2, side / 2)
            drawCircle(Color.Black.copy(alpha = 0.18f), radius = side / 2, center = c + Offset(0f, ring / 2))
            drawCircle(Color.White, radius = side / 2 - ring / 2, center = c)
            drawCircle(green, radius = side / 2 - ring, center = c, style = Stroke(ring))
            translate((side - icon) / 2, (side - icon) / 2) {
                with(painter) { draw(Size(icon, icon), colorFilter = ColorFilter.tint(green)) }
            }
        }
        img.asAndroidBitmap()
    }
}

private val SIZE = 34.dp
private val RING = 2.5.dp
private val ICON = 20.dp
