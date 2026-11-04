package com.onpath.app.ui

import com.onpath.app.navigation.ScreenTurns
import com.onpath.app.navigation.WayKind
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.JunctionCue
import com.onpath.app.routing.TurnKind
import com.onpath.app.routing.WayAttrs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.cos

/**
 * The turn card names the way like the voice (owner idea 2026-11-01): "Links auf den Radweg" on the direction line –
 * the same word source ([com.onpath.app.navigation.VoiceText.way]), no word = the plain direction, the card never grows.
 */
class TurnCardWayTest {
    private val before = Locale.getDefault()
    @After fun back() = Locale.setDefault(before)

    @Test fun germanLabels() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("Links auf den Radweg", TurnHint(TurnKind.LEFT, 150.0, onto = WayKind.CYCLEWAY).label)
        assertEquals("Rechts auf den Feldweg", TurnHint(TurnKind.RIGHT, 80.0, onto = WayKind.TRACK).label)
        assertEquals("Links", TurnHint(TurnKind.LEFT, 150.0).label)
        // roundabout, "Umdrehen", "Geradeaus": never a way word
        assertEquals(TurnHint(TurnKind.LEFT, 150.0, straight = true).label, TurnHint(TurnKind.LEFT, 150.0, straight = true, onto = WayKind.ROAD).label)
        assertEquals(TurnHint(TurnKind.LEFT, 150.0, turnRound = true).label, TurnHint(TurnKind.LEFT, 150.0, turnRound = true, onto = WayKind.ROAD).label)
    }

    @Test fun englishLabels() {
        Locale.setDefault(Locale.UK)
        assertEquals("Left onto the cycle path", TurnHint(TurnKind.LEFT, 150.0, onto = WayKind.CYCLEWAY).label)
        assertEquals("Right", TurnHint(TurnKind.RIGHT, 80.0).label)
    }

    /** A street, then right onto a cycleway: the screen's turn gets the voice's word; a line without way types none. */
    @Test fun theScreenTurnGetsTheVoicesWord() {
        val out = arrayListOf<GeoPoint>()
        var lat = 51.1673; val lon0 = 6.9274; var lon = lon0
        repeat(80) { lat += 1 / 111_320.0; out += GeoPoint(lat, lon, way = WayAttrs(wayType = "quiet_road", surface = "asphalt")) }
        out[out.size - 1] = out.last().copy(turn = JunctionCue.RIGHT, turnAngle = 90f,
            shape = com.onpath.app.routing.JunctionShape(90f, 6, 6, listOf(com.onpath.app.routing.JunctionShape.Branch(-90f, 6))))
        repeat(80) { lon += 1 / (111_320.0 * cos(Math.toRadians(lat))); out += GeoPoint(lat, lon, way = WayAttrs(wayType = "cycleway", surface = "asphalt")) }
        val turns = ScreenTurns.of(out, hike = false)
        val onto = ScreenTurns.onto(out, turns, hike = false)
        assertEquals(1, turns.size)
        assertEquals(WayKind.CYCLEWAY, onto[turns[0].index])
    }

    @Test fun theCardDoesNotGrow() {
        val card = File("src/main/java/com/onpath/app/ui/NavigateScreen.kt").readText()
            .substringAfter("private fun TurnCard(").substringBefore("\n}\n")
        // "② Leiteralm": the label may carry the stop's circle (StopText, owner 2026-11-03) – still one line, "…" at the end
        assertTrue(card, card.contains("StopText(t.label, style = MaterialTheme.typography.bodyLarge"))
        assertTrue(card, card.contains("fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis"))
    }
}
