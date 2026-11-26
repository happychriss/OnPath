package com.onpath.app.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The details sheet shows the map's symbol (owner 2026-11-17): every POI kind has its badge in the bundled sprite. */
class PoiSpriteTest {
    private val json = File("src/main/assets/${PoiSprite.JSON}").readText()

    @Test fun everyKindHasItsMapSymbol() {
        for (k in PoiKinds.all) {
            val r = PoiSprite.rect(json, k.key)
            assertNotNull("sprite badge poi-${k.key}", r)
            assertTrue("${k.key}: a 2× badge (48 px)", r!!.w == 48 && r.h == 48)
        }
        // the snack bar: the burger badge, not the dot
        val ff = PoiSprite.rect(json, "fast_food")!!
        assertEquals(listOf(288, 96), listOf(ff.x, ff.y))
    }

    @Test fun aKindWithoutASymbolKeepsItsGlyph() {
        assertNull(PoiSprite.rect(json, "ski_rental"))
    }

    /** Reading the sprite never throws: a failure is reported to Problems and the sheet shows the glyph. */
    @Test fun readingTheSpriteReportsAndFallsBack() {
        val src = File("src/main/java/com/onpath/app/poi/PoiSprite.kt").readText()
        assertTrue(src.contains("Problems.warn(\"poi\""))
        val sheet = File("src/main/java/com/onpath/app/poi/PoiSheet.kt").readText()
        assertTrue("the glyph stays the fallback", sheet.contains("kind.glyph"))
    }
}
