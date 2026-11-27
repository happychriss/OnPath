package com.onpath.app.cells

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Field bug 2026-11-27 (owner's Pixel, Madrid on Wi-Fi while the map server rebuilt): a 429 was kept as a plain
 * [CellState.Offline] and the wait window said „Keine Internetverbindung“. Only a failure the server itself answered
 * (busy, its own error) is [CellApi.ApiException.answered] – the phone is online then; no answer at all stays "offline".
 */
class ServerAnsweredTest {
    @Test fun onlyBusyAndTheServersOwnErrorCountAsAnswered() {
        val answered = CellApi.Failure.entries.filter { CellApi.ApiException(it, "x").answered }
        assertEquals(listOf(CellApi.Failure.BUSY, CellApi.Failure.SERVER).sorted(), answered.sorted())
    }
}
