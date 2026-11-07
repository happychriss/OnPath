package com.onpath.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em

/**
 * The OnPath logo and name in the app (owner 2026-11-07: Settings → Über OnPath; the system start surface draws the same
 * wordmark from `res/drawable/splash_wordmark.xml`; no head in the ☰ menu – there every row reads as a button). The logo =
 * the app icon / onpath.earth's brand.svg: green tile #1F6E43 (corner 24/108 of the icon), white mountain, yellow path;
 * the name = "OnPath" bold, letter spacing −0.05 em like the planner's header, in Walnuss.
 */
object Brand {
    const val NAME = "OnPath"
    val Tile = Color(0xFF1F6E43)
    /** The wordmark's letter spacing (the planner's header, the splash wordmark). */
    val Tracking = (-0.05).em
}

/** The logo tile at [size]: the app icon's foreground on its green background. */
@Composable
fun OnPathLogo(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * (24f / 108f))).background(Brand.Tile)) {
        Image(painterResource(com.onpath.app.R.drawable.ic_launcher_foreground), null, Modifier.size(size))
    }
}
