package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Only what needs the user goes to the top (box or pill); plain progress and "ready" do not. */
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

    /** A question = FRAGE, one way out = HINWEIS, a failure = ⚠ HINWEIS (amber), a state = the grey pill (no box). */
    @Test fun oneColourOneMeaning() {
        assertEquals(MessageKind.FRAGE, mapNoticeKind(working(MobileQuestion(3, null))))
        assertEquals(MessageKind.HINWEIS, mapNoticeKind(CellCardUi.NeedPosition(permission = false)))
        assertNull(mapNoticeKind(CellCardUi.NeedPosition(permission = true)))
        assertNull(mapNoticeKind(CellCardUi.Offline(CellCards.OFFLINE_TITLE, null)))
        assertEquals(MessageKind.HINWEIS, mapNoticeKind(CellCardUi.NotAvailable))
        assertEquals(MessageKind.HINWEIS_WARN, mapNoticeKind(CellCardUi.Failed("Download damaged – please try again later.", null, true)))
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
