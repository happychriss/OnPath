package com.onpath.app.data

import com.onpath.app.poi.PoiGroup
import com.onpath.app.poi.PoiKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Owner: all sights and viewpoints on by default – also for users who never touched the POI filter. */
class PoiDefaultsTest {
    private val dir = File(System.getProperty("java.io.tmpdir"))

    @Test fun sightsAreShownByDefault() {
        PoiKinds.inGroup(PoiGroup.SIGHT).forEach { assertTrue("${it.key} hidden", it.key !in PoiKinds.defaultHidden) }
        assertTrue("parking stays off", "parking" in PoiKinds.defaultHidden)
        assertEquals(PoiKinds.defaultHidden, Prefs(MapPrefs(), dir).settings.hiddenPoiKinds)
    }

    @Test fun untouchedOldDefaultIsUpgraded() {
        val oldDefault = setOf("shop", "parking", "toilets", "picnic", "info", "bike_shop", "charging", "church", "chapel")
        val store = MapPrefs(mutableMapOf("poiHidden" to oldDefault.toMutableSet()))
        assertEquals(PoiKinds.defaultHidden, Prefs(store, dir).settings.hiddenPoiKinds)
        assertEquals(PoiKinds.defaultHidden, Prefs(store, dir).settings.hiddenPoiKinds) // stored, next session too
    }

    @Test fun userChoiceIsKept() {
        val chosen = setOf("shop", "parking", "church", "chapel", "bus") // the user hid bus stops
        val store = MapPrefs(mutableMapOf("poiHidden" to chosen.toMutableSet()))
        assertEquals(chosen, Prefs(store, dir).settings.hiddenPoiKinds)
    }

    @Test fun upgradeRunsOnce() {
        // After the upgrade the user hides churches again (= the old default set): that choice stays.
        val store = MapPrefs()
        val p = Prefs(store, dir)
        p.settings = p.settings.copy(hiddenPoiKinds = PoiKinds.defaultHidden + setOf("church", "chapel"))
        assertEquals(PoiKinds.defaultHidden + setOf("church", "chapel"), Prefs(store, dir).settings.hiddenPoiKinds)
    }
}
