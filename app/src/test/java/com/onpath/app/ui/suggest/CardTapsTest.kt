package com.onpath.app.ui.suggest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a tap on a round-trip card lands (owner 2026-11-28 15:36, variant A): the whole text block (title, numbers,
 * text line) opens the card in front when something is cut, „⌄ mehr“ shows only then; „Übernehmen“ answers only its
 * own button; the peeking card is chosen by a tap anywhere. (The composable wires its text block through [CardTaps].)
 */
class CardTapsTest {
    @Test fun aTapOnTheTextBlockOpensTheCardWenigerClosesIt() {
        assertEquals("the text block of the card in front, something cut: opens", CardTap.OPEN, CardTaps.of(CardPart.TEXT, front = true, cut = true))
        val c = OpenCard()
        c.tapText(0)
        assertEquals("the card grew", 0, c.open)
        assertFalse("grown: no „mehr“ any more", CardTaps.showsMore(cut = true, open = true))
        c.less()
        assertEquals("„⌃ weniger“ folds it back", -1, c.open)
        assertTrue("folded: „mehr“ again", CardTaps.showsMore(cut = true, open = false))
    }

    @Test fun uebernehmenReactsOnlyToItsButton() {
        for (front in listOf(true, false)) for (cut in listOf(true, false)) {
            assertEquals(CardTap.TAKE, CardTaps.of(CardPart.TAKE, front, cut))
            assertNotEquals("the text block never takes", CardTap.TAKE, CardTaps.of(CardPart.TEXT, front, cut))
            assertNotEquals("the rest of the card never takes", CardTap.TAKE, CardTaps.of(CardPart.REST, front, cut))
        }
    }

    @Test fun aShortTextThatFitsShowsNoMehrAndChoosesLikeTheCard() {
        assertFalse(CardTaps.showsMore(cut = false, open = false))
        assertEquals("nothing cut: the text is the card – it chooses", CardTap.CHOOSE, CardTaps.of(CardPart.TEXT, front = true, cut = false))
    }

    @Test fun thePeekingCardIsChosenNotOpened() {
        assertEquals(CardTap.CHOOSE, CardTaps.of(CardPart.TEXT, front = false, cut = true))
        assertEquals(CardTap.CHOOSE, CardTaps.of(CardPart.REST, front = false, cut = true))
        assertEquals(CardTap.CHOOSE, CardTaps.of(CardPart.REST, front = true, cut = true))
    }
}
