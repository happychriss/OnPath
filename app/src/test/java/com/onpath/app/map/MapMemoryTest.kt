package com.onpath.app.map

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MapMemoryTest {
    @Test fun fileOfLocalGeoJson() {
        assertEquals(File("/storage/emulated/0/Android/data/com.onpath.app/files/onpath/cells/store/g000093/pois.geojson"),
            MapMemory.fileOf("file:///storage/emulated/0/Android/data/com.onpath.app/files/onpath/cells/store/g000093/pois.geojson"))
    }

    @Test fun onlyLocalFilesAreReleased() {
        // inline data (the app's own sources), assets, the web: nothing to free before a reload
        assertNull(MapMemory.fileOf(null))
        assertNull(MapMemory.fileOf("asset://overview/places.geojson"))
        assertNull(MapMemory.fileOf("https://example.org/a.geojson"))
        assertNull(MapMemory.fileOf("file://relative/a.geojson"))
    }

    @Suppress("DEPRECATION")
    @Test fun trimLevels() {
        // hidden / background / low memory: free the tile caches
        assertTrue(MapMemory.freesCaches(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertTrue(MapMemory.freesCaches(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertTrue(MapMemory.freesCaches(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
        assertTrue(MapMemory.freesCaches(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(MapMemory.freesCaches(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        // on screen with memory only a little tight: keep them (panning back stays smooth)
        assertFalse(MapMemory.freesCaches(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
    }
}
