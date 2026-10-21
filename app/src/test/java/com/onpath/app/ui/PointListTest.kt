package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Route ⋯ → Points…: delete without map gestures (core feature C1, second way). */
class PointListTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test fun roles() {
        val r = PointRows.of(4) { i -> if (i == 2) "Gelateria Mia" else null }
        assertEquals(listOf("Start", "Stop 2", "Stop 3", "Destination"), r.map { it.role })
        assertEquals("Gelateria Mia", r[2].name)
        assertEquals(listOf("Start"), PointRows.of(1) { null }.map { it.role })
    }

    @Test fun dialog() = paparazzi.snapshot {
        OnPathTheme { PointListDialog(PointRows.of(3) { i -> if (i == 1) "Castel Rundegg" else null }, onDelete = {}, onClose = {}) }
    }
}
