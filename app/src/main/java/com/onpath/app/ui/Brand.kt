package com.onpath.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em

/**
 * The OnPath logo and wordmark in the app (owner 2026-11-07: the ☰ menu head and Settings → Über OnPath; the system start
 * surface draws the same wordmark from `res/drawable/splash_wordmark.xml`). The logo = the app icon / onpath.earth's
 * brand.svg: green tile #1F6E43 (corner 24/108 of the icon), white mountain, yellow path; the wordmark = "OnPath" bold,
 * letter spacing −0.05 em like the planner's header, in Walnuss.
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

/** Logo + "OnPath" in a row (the ☰ menu head); read as one word by TalkBack. */
@Composable
fun OnPathWordmark(logo: Dp, style: TextStyle, modifier: Modifier = Modifier, color: Color = OnPathColors.Walnut) {
    Row(modifier.clearAndSetSemantics { contentDescription = Brand.NAME }, verticalAlignment = Alignment.CenterVertically) {
        OnPathLogo(logo)
        Spacer(Modifier.width(logo * 0.3f))
        Text(Brand.NAME, style = style.copy(fontWeight = FontWeight.Bold, letterSpacing = Brand.Tracking), color = color)
    }
}
