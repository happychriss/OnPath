package com.onpath.app.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.onpath.app.ui.AppViewModel
import com.onpath.app.ui.OverviewSnapshot

/**
 * My routes' overview map picture (moved out of MainActivity unchanged – its size cap; the day sheet uses it too): the stored
 * picture when it fits, else drawn and stored (owner 2026-11-15, swiping).
 */
@Composable
fun rememberOverviewSnapshot(vm: AppViewModel): OverviewSnapshot {
    val pad = with(androidx.compose.ui.platform.LocalDensity.current) { com.onpath.app.ui.FitPaddingRule.SIDE_DP.dp.roundToPx() }
    return remember(vm, pad) {
        { t, segs, tint, w, h, under ->
            vm.overviewShot(t, segs, tint, w, h, pad, under)?.let {
                com.onpath.app.ui.OverviewPicture(it.bitmap.asImageBitmap(),
                    androidx.compose.ui.geometry.Offset(it.startX, it.startY), androidx.compose.ui.geometry.Offset(it.goalX, it.goalY))
            }
        }
    }
}
