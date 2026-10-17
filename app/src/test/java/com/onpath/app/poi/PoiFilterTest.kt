package com.onpath.app.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoiFilterTest {
    private val known = listOf("hut", "bus", "cafe")
    private val orig = """["in",["get","kind"],["literal",["hut","bus"]]]"""

    @Test fun nothingHiddenKeepsOriginal() {
        assertEquals(orig, PoiFilter.layerFilter(orig, known, emptySet()))
        assertNull(PoiFilter.layerFilter(null, known, emptySet()))
        assertEquals(orig, PoiFilter.layerFilter(orig, known, setOf("not-a-kind")))
    }

    @Test fun wrapsOriginalFilter() {
        assertEquals(
            """["all",$orig,["in",["get","kind"],["literal",["hut","cafe"]]]]""",
            PoiFilter.layerFilter(orig, known, setOf("bus")),
        )
    }

    @Test fun noOriginalFilter() {
        assertEquals("""["in",["get","kind"],["literal",["cafe"]]]""", PoiFilter.layerFilter(null, known, setOf("hut", "bus")))
    }

    @Test fun hideAllGivesEmptyList() {
        assertEquals("""["all",$orig,["in",["get","kind"],["literal",[]]]]""",
            PoiFilter.layerFilter(orig, known, known.toSet()))
    }

    @Test fun defaultHiddenAreKnownKinds() {
        val keys = PoiKinds.all.map { it.key }.toSet()
        PoiKinds.defaultHidden.forEach { assert(it in keys) { "$it is not a known kind" } }
    }
}
