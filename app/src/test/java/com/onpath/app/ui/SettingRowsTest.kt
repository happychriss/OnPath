package com.onpath.app.ui

import com.onpath.app.data.JunctionGuide
import com.onpath.app.setup.Check
import com.onpath.app.setup.CheckId
import com.onpath.app.setup.Need
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Settings in value rows (owner 2026-11-06 23:05): where "Handy einrichten" sits on the Navigation page, what it says
 * is missing, which test the one "Testen" runs, and the value a row shows.
 */
class SettingRowsTest {
    private fun c(id: CheckId, ok: Boolean, need: Need) = Check(id, ok, need)

    @Test fun allSetUpThePhoneRowIsTheLastPlainRow() {
        val checks = listOf(c(CheckId.LOCATION, true, Need.REQUIRED), c(CheckId.BATTERY, true, Need.RECOMMENDED))
        assertEquals(SettingRows.PhoneRow.LAST, SettingRows.phoneRow(checks))
        assertEquals(emptyList<CheckId>(), SettingRows.missing(checks))
    }

    @Test fun noChecksKnownKeepsItLast() {
        assertEquals(SettingRows.PhoneRow.LAST, SettingRows.phoneRow(null))
        assertEquals(emptyList<CheckId>(), SettingRows.missing(null))
    }

    @Test fun aMissingRequiredCheckPutsItOnTopInRed() {
        val checks = listOf(c(CheckId.LOCATION, false, Need.REQUIRED), c(CheckId.BATTERY, false, Need.RECOMMENDED))
        assertEquals(SettingRows.PhoneRow.TOP_PROBLEM, SettingRows.phoneRow(checks))
    }

    @Test fun onlyARecommendedOneMissingPutsItOnTopInAmber() {
        val checks = listOf(c(CheckId.LOCATION, true, Need.REQUIRED), c(CheckId.VIBRATION, false, Need.RECOMMENDED))
        assertEquals(SettingRows.PhoneRow.TOP_WARNING, SettingRows.phoneRow(checks))
    }

    @Test fun anOptionalCheckNeverMovesItNorIsListed() {
        val checks = listOf(c(CheckId.BRAND, false, Need.OPTIONAL))
        assertEquals(SettingRows.PhoneRow.LAST, SettingRows.phoneRow(checks))
        assertEquals(emptyList<CheckId>(), SettingRows.missing(checks))
    }

    @Test fun missingListsRequiredFirstEachOnce() {
        val checks = listOf(c(CheckId.BATTERY, false, Need.RECOMMENDED), c(CheckId.LOCATION, false, Need.REQUIRED),
            c(CheckId.VIBRATION, true, Need.RECOMMENDED), c(CheckId.LOCATION, false, Need.REQUIRED))
        assertEquals(listOf(CheckId.LOCATION, CheckId.BATTERY), SettingRows.missing(checks))
    }

    @Test fun theOneTestRunsTheChosenCueKind() {
        assertEquals(CheckId.VIBRATION, SettingRows.testFor(JunctionGuide.VIBRATION))
        assertEquals(CheckId.VOICE, SettingRows.testFor(JunctionGuide.VOICE))
        assertNull(SettingRows.testFor(JunctionGuide.OFF))
    }

    @Test fun theRowShowsTheChosenWordOrTheFirstForAnUnknownValue() {
        val options = listOf(25 to "25 m", 40 to "40 m", 75 to "75 m")
        assertEquals("40 m", SettingRows.valueOf(options, 40))
        assertEquals("25 m", SettingRows.valueOf(options, 33))
        assertEquals("", SettingRows.valueOf(emptyList<Pair<Int, String>>(), 1))
    }
}
