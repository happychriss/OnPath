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

    /** Review 2026-10-24 A / C8: blue only for questions, amber for a failure, grey pill for a status. */
    @Test fun oneColourOneMeaning() {
        assertEquals(NoticeLook.QUESTION, noticeLook(working(MobileQuestion(3, null))))
        assertEquals(NoticeLook.QUESTION, noticeLook(CellCardUi.NeedPosition(permission = false)))
        assertEquals(NoticeLook.QUIET, noticeLook(CellCardUi.NeedPosition(permission = true)))
        assertEquals(NoticeLook.QUIET, noticeLook(CellCardUi.Offline(CellCards.OFFLINE_TITLE, null)))
        assertEquals(NoticeLook.QUIET, noticeLook(CellCardUi.NotAvailable))
        assertEquals(NoticeLook.WARNING, noticeLook(CellCardUi.Failed("Download damaged – please try again later.", null, true)))
        assertEquals("Use mobile data? · 1 map area", MobileQuestion(1, null).text)
    }

    @Test fun problemsAndFirstStartDo() {
        val f = CellCardUi.Failed("This area could not be prepared", null, retryable = true)
        assertEquals(f, mapNotice(f))
        assertEquals(CellCardUi.NotAvailable, mapNotice(CellCardUi.NotAvailable))
        val n = CellCardUi.NeedPosition(permission = false)
        assertEquals(n, mapNotice(n))
    }
}
