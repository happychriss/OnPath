package com.onpath.app.search

import com.onpath.app.gpx.SportType
import com.onpath.app.poi.PoiKinds
import com.onpath.app.poi.inGerman
import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Test

/** German texts of search, POIs and import (EN + DE, owner 2026-10-26): whole sentences, decimal comma. */
class GermanSearchTextsTest {
    @Test fun addPreviewInGerman() = inGerman {
        assertEquals(" · ca. +1,2 km, +20 min", AddPreview.extraText(1200.0, 1.0))
        assertEquals("ist Stopp 2", AddPreview.existingRole(1, 3))
    }

    @Test fun kindsInGerman() = inGerman {
        assertEquals("Trinkwasser", PoiKinds.of("water").label)
        assertEquals("Gipfel", SearchKinds.of("peak").label)
        assertEquals("Hütten", SearchKinds.Category.HUTS.label)
    }

    @Test fun importNoteInGerman() = inGerman {
        assertEquals("Aktivität aus der Datei übernommen: Wandern T3",
            SportType.importChoice("mountaineering", Activity.MTB, 2, touring = false).note)
    }

    @Test fun sharedPlaceNoteInGerman() = inGerman {
        assertEquals("Position aus der Adresse ermittelt", SharedPosition.of(point = false, fromPage = false, addressWon = false).note)
    }
}
