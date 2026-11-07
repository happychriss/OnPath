package com.onpath.app.debug

import com.onpath.app.debug.MainStall.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Main-thread stall classification and wording (MainStall; Pixel 2026-11-07: 11 of 13 stalls were first frames). */
class MainStallTest {
    private fun f(cls: String, method: String, line: Int = 10) = StackTraceElement(cls, method, "${cls.substringAfterLast('.')}.kt", line)

    @Test fun stallRightAfterScreenCreationIsFirstFrameInfo() {
        assertEquals(Kind.FIRST_FRAME, MainStall.kind(sinceCreatedMs = 0, leaving = false))
        assertEquals(Kind.FIRST_FRAME, MainStall.kind(sinceCreatedMs = 1_200, leaving = false))
        assertEquals(Kind.FIRST_FRAME, MainStall.kind(sinceCreatedMs = 3_000, leaving = false))
        assertEquals(Problems.Level.INFO, MainStall.level(Kind.FIRST_FRAME))
    }

    @Test fun laterStallIsWarn() {
        assertEquals(Kind.OTHER, MainStall.kind(sinceCreatedMs = 3_001, leaving = false))
        assertEquals(Kind.OTHER, MainStall.kind(sinceCreatedMs = 600_000, leaving = false))
        assertEquals(Problems.Level.WARN, MainStall.level(Kind.OTHER))
    }

    @Test fun unknownOrCrossedCreationTimeIsWarn() {
        assertEquals(Kind.OTHER, MainStall.kind(sinceCreatedMs = null, leaving = false))
        assertEquals(Kind.OTHER, MainStall.kind(sinceCreatedMs = -5, leaving = false))
    }

    @Test fun stallWhileLeavingIsWarnAndSaysSo() {
        assertEquals(Kind.LEAVING, MainStall.kind(sinceCreatedMs = 600_000, leaving = true))
        assertEquals(Kind.LEAVING, MainStall.kind(sinceCreatedMs = 500, leaving = true)) // leaving wins over first frame
        assertEquals(Kind.LEAVING, MainStall.kind(sinceCreatedMs = null, leaving = true))
        assertEquals(Problems.Level.WARN, MainStall.level(Kind.LEAVING))
    }

    @Test fun wordingPerKind() {
        val st = listOf(f("android.graphics.Matrix", "translate", 300), f("com.onpath.app.map.MapScreen", "draw", 42))
        assertEquals("Main thread blocked at the first frame after the screen was created (in: Matrix.translate:300 ← MapScreen.draw:42)",
            MainStall.message(Kind.FIRST_FRAME, st, st))
        assertEquals("Main thread blocked while leaving the app (in: Matrix.translate:300 ← MapScreen.draw:42)",
            MainStall.message(Kind.LEAVING, st, st))
        assertEquals("Main thread blocked (in: Matrix.translate:300 ← MapScreen.draw:42)",
            MainStall.message(Kind.OTHER, st, st))
    }

    @Test fun earlySampleNamedOnlyWhenDifferent() {
        val early = listOf(f("org.maplibre.android.maps.Style", "load", 88), f("com.onpath.app.map.MapStyle", "apply", 12))
        val last = listOf(f("android.graphics.Matrix", "translate", 300))
        assertEquals("Main thread blocked (in: Matrix.translate:300; at 250 ms in: Style.load:88 ← MapStyle.apply:12)",
            MainStall.message(Kind.OTHER, early, last))
        assertEquals("Main thread blocked (in: Matrix.translate:300)", MainStall.message(Kind.OTHER, emptyList(), last))
    }

    @Test fun unreadableStack() {
        assertEquals("Main thread blocked (in: ?)", MainStall.message(Kind.OTHER, emptyList(), emptyList()))
    }

    @Test fun framesKeepFrameworkAboveTheAppFrame() {
        val st = listOf(
            f("android.graphics.Matrix", "translate", 1), f("android.view.View", "draw", 2),
            f("com.onpath.app.ui.Track", "paint", 3), f("com.onpath.app.ui.Map", "render", 4),
            f("android.view.ViewGroup", "dispatchDraw", 5), f("android.os.Looper", "loop", 6),
        )
        assertEquals(listOf("Matrix.translate:1", "View.draw:2", "Track.paint:3", "Map.render:4", "ViewGroup.dispatchDraw:5"),
            MainStall.frames(st))
    }

    @Test fun deepAppFrameKeepsTopAndItself() {
        val st = (1..8).map { f("android.view.Frame$it", "m", it) } + f("com.onpath.app.MainActivity", "onStop", 99)
        assertEquals(listOf("Frame1.m:1", "Frame2.m:2", "Frame3.m:3", "…", "MainActivity.onStop:99"), MainStall.frames(st))
    }

    @Test fun noAppFrameTakesTheTop() {
        val st = (1..8).map { f("android.view.Frame$it", "m", it) }
        assertEquals(listOf("Frame1.m:1", "Frame2.m:2", "Frame3.m:3", "Frame4.m:4", "Frame5.m:5"), MainStall.frames(st))
        assertEquals(listOf("Frame1.m:1"), MainStall.frames(st, max = 1))
    }

    @Test fun eventLogLineHasTimeKindAndTwelveFrames() {
        val st = (1..15).map { f("android.view.Frame$it", "m$it", it) }
        val early = listOf(f("com.onpath.app.data.Prefs", "save", 7))
        assertEquals("Main thread blocked 812 ms while leaving the app – stack: " +
            (1..12).joinToString(" ← ") { "Frame$it.m$it" } + "; at 250 ms in: Prefs.save:7",
            MainStall.traceLine(Kind.LEAVING, 812, early, st))
        assertEquals("Main thread blocked 640 ms at the first frame after the screen was created – stack: ?",
            MainStall.traceLine(Kind.FIRST_FRAME, 640, emptyList(), emptyList()))
        assertEquals("Main thread blocked 700 ms – stack: Frame1.m1 ← Frame2.m2",
            MainStall.traceLine(Kind.OTHER, 700, emptyList(), st.take(2)))
    }

    @Test fun nativeFrameWithoutLine() {
        assertEquals("MessageQueue.nativePollOnce", MainStall.short(StackTraceElement("android.os.MessageQueue", "nativePollOnce", null, -2)))
    }
}
