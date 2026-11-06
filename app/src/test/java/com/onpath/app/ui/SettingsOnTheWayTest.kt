package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Navigate → ⚙ "on the way" (owner 2026-11-07): the Navigation page first, then only the topics that make sense while
 * riding, in a fixed order; a topic this phone does not show (e.g. Offline maps without the cell store) is left out;
 * nothing else of the full list (Backup, Sync, Updates, Diagnostics, Privacy, About, the Navigation page twice) appears.
 */
class SettingsOnTheWayTest {
    @Test fun theWayTopicsInTheirOrder() {
        assertEquals(listOf(SettingsPage.MAP, SettingsPage.POI, SettingsPage.PACE, SettingsPage.OFFLINE_MAPS),
            SettingsSummary.onTheWay(SettingsPage.entries.toList()))
    }

    @Test fun aTopicThePhoneHidesIsLeftOut() {
        val noMaps = SettingsPage.entries.filter { it != SettingsPage.OFFLINE_MAPS }
        assertEquals(listOf(SettingsPage.MAP, SettingsPage.POI, SettingsPage.PACE), SettingsSummary.onTheWay(noMaps))
    }

    @Test fun neverTheNavigationPageTwiceNorTheAppTopics() {
        val way = SettingsSummary.onTheWay(SettingsPage.entries.toList())
        assertFalse(SettingsPage.NAVIGATION in way) // it is the list itself
        listOf(SettingsPage.BACKUP, SettingsPage.SYNC, SettingsPage.UPDATES, SettingsPage.DIAGNOSTICS, SettingsPage.PRIVACY,
            SettingsPage.ABOUT, SettingsPage.PHONE).forEach { assertFalse("$it", it in way) }
    }
}
