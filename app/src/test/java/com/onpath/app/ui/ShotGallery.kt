package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.android.resources.Density as ScreenDensity

/**
 * Component galleries (rebuild phase 4b, 2026-11-19): one picture holds every state of ONE component, side by side, each
 * in a cell as wide as the phone (390 dp) under its scene name. Used where the screen around the component is not the
 * point – a change of the footer, the map controls or the sheet then re-records nothing here, and a change of the
 * component re-records one picture per language instead of one per state.
 *
 * A cell is exactly as wide as the phone, so "the text fits in the width / nothing is cut" is checked as in a
 * whole-screen picture, and the stored picture is never coarser than a whole-screen one. What a gallery does NOT check:
 * where the component sits on the screen, and its height against the screen – a cell is as high as its content wants
 * (so nothing that scrolls or fills the height can be a cell: `verticalScroll`, `LazyColumn`, `fillMaxHeight`). Scenes
 * whose point is the arrangement or the height stay whole-screen pictures ([ScreenshotTest]). Font size: the test's
 * device ([Paparazzi]'s `fontScale`), not per cell – a phone scales large fonts non-linearly, a density override would not.
 */
object ShotGallery {
    /** The phone width of the whole-screen pictures ([ScreenshotTest]: 1024 px at 420 dpi). */
    private const val CELL_DP = 390
    private const val GAP_DP = 16
    private const val DPI = 420
    val CELL: Dp = CELL_DP.dp
    val GAP: Dp = GAP_DP.dp
    private fun px(dp: Int) = dp * DPI / 160
    const val COLUMNS = 2

    /**
     * A Paparazzi for a gallery: two cells in a row, at most as high as the phone's screen; the picture shrinks to what
     * is drawn. Not bigger on purpose: Paparazzi stores every picture scaled to 1000 px on its longer side, so a gallery
     * larger than a whole-screen picture (2400 px) would store its cells smaller than before and a cut letter could
     * slip through. [fontScale] = the device's font size (a phone scales large fonts non-linearly, so it is not a cell's
     * density). [Gallery] fails when its cells do not fit.
     */
    fun paparazzi(fontScale: Float = 1f) = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            // + a few px: Compose rounds every dp size on its own
            screenWidth = px(COLUMNS * CELL_DP + (COLUMNS + 1) * GAP_DP) + 4, screenHeight = 2400,
            xdpi = 428, ydpi = 428, density = ScreenDensity.create(420), fontScale = fontScale,
        ),
        showSystemUi = false,
        renderingMode = SessionParams.RenderingMode.SHRINK,
    )
}

class GalleryCells {
    internal val cells = ArrayList<Pair<String, @Composable () -> Unit>>()
    /** One state: [name] is printed above it (the former scene name). */
    fun cell(name: String, content: @Composable () -> Unit) { cells.add(name to content) }
}

/**
 * The cells in rows of two, on a neutral ground; labels in a fixed small type that no font scale touches.
 * Fails when the cells need more room than the picture has (nothing may be cut off silently) – split the gallery then.
 */
@Composable
fun Gallery(build: GalleryCells.() -> Unit) {
    val columns = ShotGallery.COLUMNS
    val all = GalleryCells().apply(build).cells
    val base = LocalDensity.current
    Layout(content = { GalleryGrid(columns, all, base) }) { measurables, constraints ->
        val p = measurables.single().measure(Constraints())
        check(p.width <= constraints.maxWidth && p.height <= constraints.maxHeight) {
            "The gallery needs ${p.width} x ${p.height} px, the picture has ${constraints.maxWidth} x ${constraints.maxHeight} – split it into two pictures"
        }
        layout(p.width, p.height) { p.place(0, 0) }
    }
}

@Composable
private fun GalleryGrid(columns: Int, all: List<Pair<String, @Composable () -> Unit>>, base: Density) {
    Column(Modifier.background(Color(0xFFE9E9E9)).padding(ShotGallery.GAP), verticalArrangement = Arrangement.spacedBy(ShotGallery.GAP)) {
        all.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ShotGallery.GAP)) {
                row.forEach { (name, content) ->
                    Column(Modifier.width(ShotGallery.CELL)) {
                        // the label in a fixed small type that the device's font scale does not touch
                        CompositionLocalProvider(LocalDensity provides Density(base.density, 1f)) {
                            BasicText(name, Modifier.padding(bottom = 4.dp), style = TextStyle(fontSize = 11.sp, color = Color(0xFF444444)))
                        }
                        Box(Modifier.width(ShotGallery.CELL)) { content() }
                    }
                }
            }
        }
    }
}

/**
 * Drop-down menus are popups (not drawn by Paparazzi): their items in a card that looks like Material's menu.
 * The place of the card is the caller's – an imitation, never a check of where the app opens the menu.
 */
@Composable
fun MenuCard(modifier: Modifier, items: @Composable () -> Unit) {
    Surface(modifier.width(260.dp), shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 3.dp) {
        Column(Modifier.padding(vertical = 8.dp)) { items() }
    }
}
