package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Owner 2026-10-23: only what needs the user goes to the blue strip; plain progress and "ready" do not. */
class MapNoticeTest {
    private fun working(q: MobileQuestion? = null, wifi: String? = null) =
        CellCardUi.Working("Getting the detailed map", null, emptyList(), null, 0.4f, q, wifi)

    @Test fun questionAndWaitingGoToTheStrip() {
        val q = working(MobileQuestion(3, 25L * 1_048_576))
        assertEquals(q, mapNotice(q))
        val w = working(wifi = "3 areas wait for Wi-Fi (≈ 25 MB)")
        assertEquals(w, mapNotice(w))
    }

    @Test fun plainProgressAndReadyDoNot() {
        assertNull(mapNotice(working()))
        assertNull(mapNotice(CellCardUi.Done(3, 30L * 1_048_576)))
        assertNull(mapNotice(null))
    }

    @Test fun problemsAndFirstStartDo() {
        val f = CellCardUi.Failed("This area could not be prepared", null, retryable = true)
        assertEquals(f, mapNotice(f))
        assertEquals(CellCardUi.NotAvailable, mapNotice(CellCardUi.NotAvailable))
        val n = CellCardUi.NeedPosition(permission = false)
        assertEquals(n, mapNotice(n))
    }
}
