package com.onpath.app.search

import com.onpath.app.search.SearchText.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {

    @Test fun normalizeStripsAccentsAndPunctuation() {
        assertEquals("meraner hutte", SearchText.normalize("Meraner Hütte"))
        assertEquals("weisskugel", SearchText.normalize("Weißkugel"))
        assertEquals("pippos mountain lodge", SearchText.normalize("Pippo’s Mountain Lodge"))
        assertEquals("rifugio citta di merano", SearchText.normalize("Rifugio Città di Merano"))
        assertEquals("hohe weisse cima bianca", SearchText.normalize("Hohe Weiße - Cima Bianca"))
        assertEquals("", SearchText.normalize(null))
        assertEquals(listOf("st", "leonhard"), SearchText.tokens("St. Leonhard"))
    }

    @Test fun wordMatches() {
        assertEquals(Match.EXACT, SearchText.matchWord("meran", "meran"))
        assertEquals(Match.PREFIX, SearchText.matchWord("mer", "meraner"))
        assertEquals(Match.INFIX, SearchText.matchWord("joch", "hochjoch"))
        assertEquals(Match.NONE, SearchText.matchWord("jo", "hochjoch")) // too short for inside-word
    }

    @Test fun oneTypoForLongWords() {
        assertEquals(Match.TYPO, SearchText.matchWord("merna", "meran"))       // swap
        assertEquals(Match.TYPO, SearchText.matchWord("rifugo", "rifugio"))    // missing letter
        assertEquals(Match.TYPO, SearchText.matchWord("huette", "hutte"))      // "ue" spelling
        assertEquals(Match.TYPO, SearchText.matchWord("hochganh", "hochganghaus")) // typo in a prefix
        assertEquals(Match.NONE, SearchText.matchWord("mera", "mira"))         // 4 letters: no typo tolerance
        assertEquals(Match.NONE, SearchText.matchWord("merxxn", "meran"))      // two edits
    }

    @Test fun osaDistance() {
        assertTrue(SearchText.osaAtMostOne("abcde", "abdce"))
        assertTrue(SearchText.osaAtMostOne("abcde", "abcd"))
        assertTrue(SearchText.osaAtMostOne("abcd", "xabcd"))
        assertFalse(SearchText.osaAtMostOne("abcde", "badce"))
        assertFalse(SearchText.osaAtMostOne("abc", "abcde"))
    }

    @Test fun kindWords() {
        assertTrue("hut" in SearchText.kindsFor("hutte"))
        assertTrue("hut" in SearchText.kindsFor("rifugio"))
        assertTrue("train" in SearchText.kindsFor("bahnh"))   // prefix of "bahnhof"
        assertTrue("lift" in SearchText.kindsFor("station"))
        assertTrue(SearchText.kindsFor("ba").isEmpty())
    }
}
