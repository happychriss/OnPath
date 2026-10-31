package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The top bar's title never breaks inside a word: the action drops its label first, the title ellipsizes last. */
class TopBarLayoutTest {
    // dp at a Pixel 5 (393 dp wide): back 56 + ⋮ 48 + padding 8 = 112 fixed
    private val row = 393
    private val fixed = 112

    @Test fun normalFontKeepsTheFullAction() {
        // "Routen" ~ 70 dp at 22 sp, "GPX importieren" button ~ 160 dp
        assertEquals(TopBarLayout.Fit.FULL, TopBarLayout.fit(row, fixed, titleWord = 70, actionWidth = 160, iconWidth = 48))
    }

    @Test fun largeFontDropsTheLabel() {
        // font 2.0: "Routen" ~ 140 dp, "GPX importieren" ~ 290 dp → the row would leave the title a few letters
        assertEquals(TopBarLayout.Fit.ICON, TopBarLayout.fit(row, fixed, titleWord = 140, actionWidth = 290, iconWidth = 48))
    }

    @Test fun ellipsizesOnlyWhenEvenTheIconLeavesNoRoomForTheWord() {
        assertEquals(TopBarLayout.Fit.ICON, TopBarLayout.fit(row, fixed, titleWord = row - fixed - 48, actionWidth = 290, iconWidth = 48))
        assertEquals(TopBarLayout.Fit.ELLIPSIZE, TopBarLayout.fit(row, fixed, titleWord = row - fixed - 47, actionWidth = 290, iconWidth = 48))
    }

    @Test fun fitsIsInclusive() {
        assertTrue(TopBarLayout.fits(300, 100, 100, 100))
        assertFalse(TopBarLayout.fits(300, 100, 101, 100))
    }
}
