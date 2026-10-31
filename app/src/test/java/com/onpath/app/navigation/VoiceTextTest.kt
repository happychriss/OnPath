package com.onpath.app.navigation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The spoken sentences (docs/design/voice-guidance.md §3) in English and German. */
class VoiceTextTest {
    private val saved = Locale.getDefault()
    @After fun back() = Locale.setDefault(saved)

    private fun de(block: () -> Unit) { Locale.setDefault(Locale.GERMANY); block() }

    @Test fun english() {
        assertEquals("In 100 meters, turn left.", VoiceText.of(Phrase.Junction(110.0, Turn.LEFT)))
        assertEquals("Now turn sharp right.", VoiceText.of(Phrase.Junction(20.0, Turn.SHARP_RIGHT)))
        assertEquals("In 50 meters, turn left onto the track, then turn right.",
            VoiceText.of(Phrase.Junction(60.0, Turn.LEFT, onto = WayKind.TRACK, then = Turn.RIGHT)))
        assertEquals("In 100 meters, turn right, then 3 kilometers straight on.",
            VoiceText.of(Phrase.Junction(100.0, Turn.RIGHT, straightM = 3_040.0)))
        assertEquals("In 150 meters, at the roundabout take the second exit.", VoiceText.of(Phrase.Junction(160.0, Turn.ROUNDABOUT, exit = 2)))
        assertEquals("Off we go. 12 kilometers to Lana, about 50 minutes.", VoiceText.of(Phrase.Start(12_200.0, 2_950.0, "Lana")))
        assertEquals("Gasthof Hochmuth reached. On to Algund, 4 kilometers.",
            VoiceText.of(Phrase.StopReached("Gasthof Hochmuth", 2, "Algund", 4_020.0)))
        assertEquals("Stop 3 reached.", VoiceText.of(Phrase.StopReached(null, 3, null, null)))
    }

    @Test fun german() = de {
        assertEquals("In 100 Metern links.", VoiceText.of(Phrase.Junction(110.0, Turn.LEFT)))
        assertEquals("Gleich scharf rechts.", VoiceText.of(Phrase.Junction(20.0, Turn.SHARP_RIGHT)))
        assertEquals("In 50 Metern links auf den Feldweg, danach gleich rechts.",
            VoiceText.of(Phrase.Junction(60.0, Turn.LEFT, onto = WayKind.TRACK, then = Turn.RIGHT)))
        assertEquals("In 100 Metern rechts, dann 3 Kilometer geradeaus.", VoiceText.of(Phrase.Junction(100.0, Turn.RIGHT, straightM = 3_040.0)))
        assertEquals("In 1,5 Kilometern links halten.", VoiceText.of(Phrase.Junction(1_480.0, Turn.KEEP_LEFT)))
        assertEquals("In 150 Metern im Kreisverkehr die zweite Ausfahrt.", VoiceText.of(Phrase.Junction(160.0, Turn.ROUNDABOUT, exit = 2)))
        assertEquals("Los geht's. 12 Kilometer bis Lana, etwa 50 Minuten.", VoiceText.of(Phrase.Start(12_200.0, 2_950.0, "Lana")))
        assertEquals("Los geht's. 1,5 Kilometer, etwa eine Stunde 20 Minuten.", VoiceText.of(Phrase.Start(1_500.0, 4_700.0, null)))
        assertEquals("Neue Route. In 200 Metern rechts.", VoiceText.of(Phrase.NewRoute(Phrase.Junction(210.0, Turn.RIGHT))))
        assertEquals("Gasthof Hochmuth erreicht. Weiter nach Algund, 4 Kilometer.",
            VoiceText.of(Phrase.StopReached("Gasthof Hochmuth", 2, "Algund", 4_020.0)))
        assertEquals("Kein Ziel mehr vor dir. Bitte aufs Handy schauen.", VoiceText.of(Phrase.NoDestination))
        assertEquals("Ziel erreicht.", VoiceText.of(Phrase.Arrived))
    }

    @Test fun roundedDistancesOneCanPicture() {
        assertEquals(listOf(50, 50, 100, 150, 200, 300, 500, 800),
            listOf(30.0, 70.0, 110.0, 160.0, 240.0, 330.0, 600.0, 820.0).map { VoiceText.roundAhead(it) })
    }
}
