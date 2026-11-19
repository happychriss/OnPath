package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.onpath.app.suggest.SuggestAsk
import com.onpath.app.suggest.SuggestCard
import com.onpath.app.suggest.SuggestCards
import com.onpath.app.suggest.SuggestPill
import com.onpath.app.ui.suggest.SuggestHost
import com.onpath.app.ui.suggest.SuggestPhase
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.common_cancel
import com.onpath.strings.common_ok
import com.onpath.strings.wait_step_suggest_read
import com.onpath.strings.wait_title_suggest
import com.onpath.strings.suggest_ui_from_here
import com.onpath.strings.suggest_ui_less
import com.onpath.strings.suggest_ui_take
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Round-trip suggestions on the phone (docs/design/tour-suggestions.md §7 stage 4), on by default since 2026-11-18: a hold
 * on the empty map → „Rundtour von hier“ (right under „Hier starten“) → the wish sheet → OK → the cards in the plan
 * sheet's place, side by side in one row (sketch 8) → a tap on the first card's text grows it, „weniger“ folds it (V1,
 * owner 2026-11-19) → a swipe to the second card folds it and draws its loop (the one suggestion line on the map) →
 * „Übernehmen“.
 *
 * The method itself is not run here: the test map (Meran) has no suggest file, so the run is a stand-in answer
 * ([SuggestHost.suggest] replaced, everything else – camera, „Übernehmen“ through TrackToPlan – is the app's own).
 * With real routing data (the workflow's E10_N45.rd5) „Übernehmen“ must give a plan; without it nothing may crash.
 */
@RunWith(AndroidJUnit4::class)
class RoundTripSuggestE2ETest : E2EBase() {

    @Test fun holdRoundTripWishCardsLoopTake() {
        launch()
        val asks = ArrayList<SuggestAsk>()
        scenario.onActivity { a ->
            val s = vm(a).suggest
            assertTrue("on for everyone since 2026-11-18 (owner), no switch needed", s.on)
            val real = s.host
            s.host = object : SuggestHost by real {
                override suspend fun suggest(ask: SuggestAsk, lapped: (String, Double) -> Unit): SuggestCards {
                    asks += ask; return answer(ask.lat, ask.lon)
                }
            }
        }
        val (w, h) = mapSize()
        hold(w * 0.45f, h * 0.35f, takeAddMenu = false)
        waitUntil("the first-point menu", 5_000) { read { it.placeAdd.firstPointMenu } != null }
        clickText(Txt.s(Res.string.suggest_ui_from_here))
        waitUntil("the wish sheet", 5_000) { read { it.suggest.wishOpen } }
        shot("suggest-wish")
        clickText(Txt.s(Res.string.common_ok))
        waitUntil("the cards", 10_000) { read { it.suggest.phase } == SuggestPhase.CARDS }
        assertEquals("one run for the wish", 1, asks.size)
        assertEquals("the hike's pre-set: 2–3 h", 2.0, asks[0].hours[0], 0.0)
        assertNotNull("the first card", findFresh(By.text("Runde am Hang"), 5_000))
        shot("suggest-cards")

        // owner 2026-11-19 (V1): a tap on the card's cut text grows the card in place with the whole text, „▴ weniger“
        // folds it back; a swipe to another card folds it too
        val less = Txt.s(Res.string.suggest_ui_less)
        assertTrue("folded at first", findFresh(By.text(less), 1_000) == null)
        findFresh(By.text(LONG), 5_000)!!.click()
        assertNotNull("the card grew: „weniger“ under its text", findFresh(By.text(less), 5_000))
        shot("suggest-card-grown")
        assertEquals("the map keeps the chosen loop", 0, read { it.suggest.chosen })
        findFresh(By.text(less), 2_000)!!.click()
        waitUntil("„weniger“ folded it back", 5_000) { findFresh(By.text(less), 500) == null }
        findFresh(By.text(LONG), 5_000)!!.click()
        assertNotNull("grown again", findFresh(By.text(less), 5_000))

        // the cards sit side by side in one row (sketch 8): a swipe to the left brings the second card to the front
        val first = findFresh(By.text("Runde am Hang"), 5_000)!!.visibleBounds
        val (sw, _) = mapSize()
        device.swipe((sw * 0.85f).toInt(), first.centerY(), (sw * 0.15f).toInt(), first.centerY(), 25)
        waitUntil("the second card chosen by the swipe", 5_000) { read { it.suggest.chosen } == 1 }
        waitUntil("the swipe folded the grown card", 5_000) { findFresh(By.text(less), 500) == null }
        assertEquals("only the chosen loop is on the map", read { it.suggest.cards[1].points() }, read { it.suggest.loop })
        waitUntil("its line drawn by MapLibre", 10_000) { loopDrawn() }
        shot("suggest-loop")

        // the button may be recomposed under the finger's search (the camera fit moves the sheet): find it fresh, tap
        val take = Txt.s(Res.string.suggest_ui_take)
        waitUntil("„Übernehmen“ tapped", 10_000) {
            // every card has one (sketch 8): the chosen card's is the one shown whole, not the sliver of a card beside it
            runCatching {
                findFresh(By.text(take), 2_000) // a fresh look at the screen (the cache cleared) …
                val b = device.findObjects(By.text(take)).map { it.visibleBounds }.maxByOrNull { it.width() } ?: return@runCatching false
                device.click(b.centerX(), b.centerY()) // … then the widest one, by its place (a node may go stale)
            }.getOrDefault(false) // expected: a stale node – again
        }
        if (realRouting) {
            waitUntil("the loop as a plan", 60_000) { waypointCount() >= 2 }
            assertEquals("the suggestions closed", null, read { it.suggest.phase })
            assertTrue("a round trip: it ends where it starts", read { it.isRoundTrip() })
        } else {
            // no routing data in this run: TrackToPlan cannot fit – the cards stay (or the plan is there), no crash
            waitUntil("Übernehmen answered", 30_000) { !read { it.suggest.taking } }
            assertTrue(read { it.suggest.phase == SuggestPhase.CARDS || it.plan.points.size >= 2 })
        }
        shot("suggest-taken")
    }

    /**
     * Owner 2026-11-19: the search is form A – the wait window over the map with its steps and „Abbrechen“; a tap or a
     * hold on the map meanwhile changes nothing (before, it ended the search and set a point instead). „Abbrechen“ ends
     * the search and leaves the map as it was.
     */
    @Test fun theSearchWaitsInTheWindowTheMapIgnoresPressesCancelEndsIt() {
        launch()
        val asks = ArrayList<SuggestAsk>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        scenario.onActivity { a ->
            val s = vm(a).suggest
            val real = s.host
            s.host = object : SuggestHost by real {
                override suspend fun suggest(ask: SuggestAsk, lapped: (String, Double) -> Unit): SuggestCards {
                    asks += ask
                    lapped("network", 1.0) // the first step's half is behind; then the search waits
                    release.await()
                    return answer(ask.lat, ask.lon)
                }
            }
        }
        val (w, h) = mapSize()
        hold(w * 0.45f, h * 0.35f, takeAddMenu = false)
        waitUntil("the first-point menu", 5_000) { read { it.placeAdd.firstPointMenu } != null }
        clickText(Txt.s(Res.string.suggest_ui_from_here))
        waitUntil("the wish sheet", 5_000) { read { it.suggest.wishOpen } }
        val start = read { it.suggest.wish!!.at }
        clickText(Txt.s(Res.string.common_ok))
        assertNotNull("the wait window", findFresh(By.text(Txt.s(Res.string.wait_title_suggest)), 5_000))
        assertNotNull("its steps", findFresh(By.text(Txt.s(Res.string.wait_step_suggest_read)), 2_000))
        shot("suggest-wait")

        // a tap and a hold on the map: nothing happens – no menu, no point, the same search for the same start
        device.click((w * 0.3f).toInt(), (h * 0.6f).toInt())
        hold(w * 0.6f, h * 0.55f, takeAddMenu = false)
        Thread.sleep(800)
        assertTrue("still searching", read { it.suggest.searching })
        assertEquals("the start stays", start, read { it.suggest.wish!!.at })
        assertEquals("no point set", 0, waypointCount())
        assertEquals("no menu opened", null, read { it.placeAdd.firstPointMenu })
        assertEquals("one run", 1, asks.size)

        clickText(Txt.s(Res.string.common_cancel))
        waitUntil("the search ended", 5_000) { read { it.suggest.phase } == null }
        assertTrue("the window is gone", findFresh(By.text(Txt.s(Res.string.wait_title_suggest)), 1_000) == null)
        assertEquals("the map as before: no point", 0, waypointCount())
        assertEquals("no loop on the map", null, read { it.suggest.loop })
        release.complete(Unit)
    }

    private companion object {
        /** The first card's text: longer than its one line on the card (the tap grows the card). */
        const val LONG = "Am Waalweg entlang durch den Wald, über die Wiesen hinauf zum Hof mit Blick ins Tal, " +
            "dann auf dem alten Steig zurück zum Start."
    }

    /** Two loops of ~1.5 km around the held spot (closed: first = last). */
    private fun answer(lat: Double, lon: Double): SuggestCards {
        fun loop(dLon: Double) = doubleArrayOf(lon, lat, lon + dLon, lat + 0.004, lon + dLon * 2, lat, lon + dLon, lat - 0.004, lon, lat)
        val cards = listOf(
            SuggestCard(1, "Runde am Hang", null, null, 4.2, 1.2, 180, LONG, listOf(SuggestPill("grün", true, 2)), loop(0.005)),
            SuggestCard(2, "Runde im Tal", "Rundweg", 2.0, 3.6, 1.0, 60, "am Bach entlang", listOf(SuggestPill("lauter", false, 1)), loop(-0.005)),
        )
        return SuggestCards(cards, emptyList(), false, doubleArrayOf(2.0, 3.0), intArrayOf(0, 400), 2, emptyMap())
    }

    /** MapLibre draws the suggestion line ([com.onpath.app.map.MapController.SRC_SUGGEST]) on the screen. */
    private fun loopDrawn(): Boolean {
        var drawn = false
        val done = CountDownLatch(1)
        scenario.onActivity { a ->
            val mv = mapView(a)!!
            mv.getMapAsync { m ->
                drawn = m.queryRenderedFeatures(android.graphics.RectF(0f, 0f, mv.width.toFloat(), mv.height.toFloat()),
                    com.onpath.app.map.MapController.SRC_SUGGEST).isNotEmpty()
                done.countDown()
            }
        }
        done.await(5, TimeUnit.SECONDS)
        return drawn
    }

    private fun <T> read(f: (com.onpath.app.ui.AppViewModel) -> T): T {
        var v: T? = null
        scenario.onActivity { v = f(vm(it)) }
        @Suppress("UNCHECKED_CAST") return v as T
    }
}
