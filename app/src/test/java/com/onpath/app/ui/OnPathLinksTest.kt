package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** About → links (owner 2026-11-08): the website's pages in the app language – German under /de/, English at the root. */
class OnPathLinksTest {
    @Test fun germanPages() {
        assertEquals("https://onpath.earth/de/", OnPathLinks.website("de"))
        assertEquals("https://onpath.earth/de/hilfe/", OnPathLinks.help("de"))
        assertEquals("https://onpath.earth/de/datenschutz/", OnPathLinks.privacy("de"))
        assertEquals("https://onpath.earth/de/impressum/", OnPathLinks.legal("de"))
    }

    @Test fun englishPages() {
        assertEquals("https://onpath.earth/", OnPathLinks.website("en"))
        assertEquals("https://onpath.earth/help/", OnPathLinks.help("en"))
        assertEquals("https://onpath.earth/privacy/", OnPathLinks.privacy("en"))
        assertEquals("https://onpath.earth/legal/", OnPathLinks.legal("en"))
    }

    @Test fun anyOtherLanguageGetsEnglish() = assertEquals("https://onpath.earth/help/", OnPathLinks.help("fr"))

    @Test fun shownWithoutSchemeAndLastSlash() {
        assertEquals("onpath.earth/de/hilfe", OnPathLinks.shown(OnPathLinks.help("de")))
        assertEquals("onpath.earth", OnPathLinks.shown(OnPathLinks.website("en")))
        assertEquals("github.com/happychriss/OnPath", OnPathLinks.shown(OnPathLinks.SOURCE))
    }
}
