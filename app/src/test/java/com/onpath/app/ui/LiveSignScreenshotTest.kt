package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.recording.GpsStatus
import com.onpath.app.routing.Activity
import com.onpath.app.routing.TurnKind
import com.onpath.app.ui.live.LiveAction
import com.onpath.app.ui.live.LiveSign
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * "Standort teilen" on Navigate (owner 2026-11-24): the share button on top of the map column's tour bubble (⚙ below it,
 * a 12 dp gap, then the map bubble), plain off / green filled live / grey filled waiting; the pill under the status card
 * only while waiting. At a real phone's size in German, English, the largest font and a small screen. Guards: nothing
 * overlaps, the pill wraps and keeps clear of the column, no sign in the turn card any more.
 */
abstract class LiveSignShots(private val locale: Locale) {
    abstract val paparazzi: Paparazzi

    private val before = Locale.getDefault()
    @Before fun locale() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    private val route = NavUi(toGo = ToGo(7_420.0, 512.0, 2.9 * 3600, 12.0, descentM = 310.0), recordedM = 5_320.0, elapsedS = 5025,
        activity = Activity.HIKE, arriveAt = "16:40", turn = TurnHint(TurnKind.RIGHT, 120.0))
    private val free = NavUi(recordedM = 2_150.0, elapsedS = 1820, paused = false)

    /** [sign] null = sharing not offered (the tour bubble holds only ⚙). */
    @Composable
    protected fun Frame(ui: NavUi, sign: LiveSign?) {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    CompositionLocalProvider(
                        LocalNavBelowCard provides { if (sign != null) LiveWaitingPill(sign) {} },
                        LocalTourShare provides if (sign == null) null else { size -> LiveShareButton(sign, size) {} },
                    ) {
                        NavigateOverlay(ui, footer = {
                            MapFooter("500 m" to 64.dp) {
                                MapControls(CompassSpec(ui.headingUp, 0f) {}, showEye = true, onPeek = {}, gps = GpsStatus.GOOD,
                                    following = true, onLocate = {}, onFitRoute = {}, onSettings = {})
                            }
                        }, onSlideToPlan = {})
                    }
                }
            }
        }
    }

    @Test fun freeTourOff() = paparazzi.snapshot { Frame(free, LiveSign.NONE) }
    @Test fun plannedLive() = paparazzi.snapshot { Frame(route, LiveSign.LIVE) }
    @Test fun plannedWaiting() = paparazzi.snapshot { Frame(route, LiveSign.WAITING) }
    /** Sharing not offered (no tour records / not unlocked): ⚙ alone in its bubble. */
    @Test fun notOffered() = paparazzi.snapshot { Frame(route, null) }
}

private val signPhone = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2400, xdpi = 420, ydpi = 420, density = Density.create(420))

class LiveSignScreenshotTest : LiveSignShots(Locale.GERMANY) {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = signPhone, showSystemUi = false)
}

class LiveSignEnglishScreenshotTest : LiveSignShots(Locale.US) {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = signPhone, showSystemUi = false)
}

class LiveSignLargeFontTest : LiveSignShots(Locale.GERMANY) {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = signPhone.copy(fontScale = 2.0f), showSystemUi = false)
}

/** A small screen (411 x 640 dp) at the largest font: both bubbles, the card and the waiting pill share little room. */
class LiveSignSmallLargeFontTest : LiveSignShots(Locale.GERMANY) {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = signPhone.copy(screenHeight = 1680, fontScale = 2.0f), showSystemUi = false)
}
