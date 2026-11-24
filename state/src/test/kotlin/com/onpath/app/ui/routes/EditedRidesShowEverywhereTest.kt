package com.onpath.app.ui.routes

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.map.ThumbRules
import com.onpath.app.rides.Ride
import com.onpath.app.rides.RideApply
import com.onpath.app.rides.RideChoice
import com.onpath.app.rides.RideKind
import com.onpath.app.rides.RideRecord
import com.onpath.app.rides.RideStore
import com.onpath.app.rides.TourStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Fahrten bearbeiten" (owner 2026-11-21): "Allerdings sieht man von der Anpassung nichts." After a choice is applied
 * ([RideApply.edit], what `TourEndSlice.applyEditRides` calls) every place that shows the tour must show the new state:
 * the row's map picture (made again: its key knows the choice), the line the pictures and the overview draw (only what the
 * choice keeps), the line cache of the overview. A tour without a ride record is untouched by all of this.
 */
class EditedRidesShowEverywhereTest {
    private val pts = (0..9).map { TrackPoint(46.0 + it * 0.001, 11.0, 100.0 + it, 1_000_000L + it * 60_000L) }
    private val ride = Ride(3, 6, RideKind.VEHICLE)
    private val recorded = TourStats(1000.0, 90.0, 0.0, 540.0)

    /** The tour table and the points in memory, as TourDb gives them to [RideApply]. */
    private inner class Db(var tour: Tour) : RideStore, RoutesStore {
        override fun tour(id: Long) = tour
        override fun segments(id: Long) = listOf(pts)
        override fun setRides(id: Long, rec: RideRecord?, stats: TourStats): Boolean {
            tour = tour.copy(rides = rec, distanceM = stats.distanceM, ascentM = stats.ascentM, descentM = stats.descentM, durationS = stats.durationS)
            return true
        }
        override fun insertPart(from: Tour, name: String, segments: List<List<TrackPoint>>, stats: TourStats, createdMs: Long) = error("no split")
        override fun listTours() = listOf(tour)
        override fun listDeleted() = emptyList<Tour>()
        override fun setDraft(id: Long, draft: Boolean) = true
        override fun setDeleted(id: Long, deletedMs: Long?) = true
        override fun loadSegments(id: Long) = listOf(pts)
        override fun deleteForGood(id: Long) {}
        override fun rename(id: Long, name: String) {}
    }

    private fun savedAsRecorded() = Db(Tour(1, TourKind.RECORDED, "T", 1, "HIKE", 2, recorded.distanceM, recorded.ascentM, recorded.descentM,
        recorded.durationS, "done", emptyList(), "u1", rides = RideRecord(listOf(ride), RideChoice.AS_RECORDED, recorded, 1_540_000L)))

    @Test fun theRowsMapPictureIsMadeAgainAfterAnEditNotOnlyReKeyed() {
        val db = savedAsRecorded()
        val before = db.tour
        val lineKey = "10:460.045:110.000" // the stored points never change
        val stored = mapOf("u1" to ThumbRules.key(before, lineKey))
        assertTrue("nothing to do before the edit", ThumbRules.plan(listOf(before), { stored[it] }, { lineKey }).let { it.make.isEmpty() && it.rekey.isEmpty() })
        RideApply.edit(db, 1, RideChoice.UNTIL)
        val plan = ThumbRules.plan(listOf(db.tour), { stored[it] }, { lineKey })
        assertEquals("a new picture, not just a new key on the old picture", listOf(1L), plan.make.map { it.first.id })
        assertTrue(plan.rekey.isEmpty())
    }

    @Test fun theKeyKnowsTheChoiceAndTheSameChoiceKeepsItsPicture() {
        val db = savedAsRecorded()
        val asRecorded = ThumbRules.key(db.tour, "k")
        RideApply.edit(db, 1, RideChoice.NOT_COUNTED)
        val notCounted = ThumbRules.key(db.tour, "k")
        RideApply.edit(db, 1, RideChoice.UNTIL)
        val until = ThumbRules.key(db.tour, "k")
        assertNotEquals(asRecorded.substringAfter('|'), notCounted.substringAfter('|'))
        assertNotEquals(notCounted.substringAfter('|'), until.substringAfter('|'))
        RideApply.edit(db, 1, RideChoice.NOT_COUNTED)
        assertEquals(notCounted, ThumbRules.key(db.tour, "k"))
        // a tour without a record, and one read as recorded, keep the keys they always had (no mass redraw)
        assertEquals(asRecorded.substringAfter('|'), ThumbRules.key(db.tour.copy(rides = null), "k").substringAfter('|'))
    }

    @Test fun thePicturesAndTheOverviewDrawWhatTheChoiceKeeps() {
        val db = savedAsRecorded()
        val state = RoutesState(db)
        assertEquals("as recorded: every point", 10, state.line(db.tour) { emptyList() }.sumOf { it.size })
        RideApply.edit(db, 1, RideChoice.UNTIL)
        val until = state.line(db.tour) { emptyList() }
        assertEquals("cut at the ride: the points up to where the user got on", listOf(4), until.map { it.size })
        assertEquals(pts[3].lat, until.single().last().lat, 1e-9)
        RideApply.edit(db, 1, RideChoice.NOT_COUNTED)
        assertEquals("the ride is not part of the drawn tour, before and after it are", listOf(4, 4), state.line(db.tour) { emptyList() }.map { it.size })
    }

    @Test fun theOverviewsLineIsNotKeptFromBeforeTheEdit() {
        val db = savedAsRecorded()
        val a = RouteLineKey.of(db.tour)
        RideApply.edit(db, 1, RideChoice.UNTIL)
        assertNotEquals(a, RouteLineKey.of(db.tour))
        // the same numbers under another choice (a ride of no length): still another line
        val same = db.tour.copy(rides = db.tour.rides!!.copy(choice = RideChoice.NOT_COUNTED))
        assertNotEquals(RouteLineKey.of(db.tour), RouteLineKey.of(same))
        RideApply.edit(db, 1, RideChoice.AS_RECORDED)
        assertEquals("back to the first look: the first key", a, RouteLineKey.of(db.tour))
    }

    private fun saved(r: Ride, c: RideChoice) = Db(Tour(1, TourKind.RECORDED, "T", 1, "HIKE", 2, recorded.distanceM, recorded.ascentM, recorded.descentM,
        recorded.durationS, "done", emptyList(), "u1", rides = RideRecord(listOf(r), c, recorded, 1_540_000L)))

    @Test fun aPictureFromBeforeTheUpdateOfATourWithAChoiceIsMadeAgainOnce() {
        val t = saved(ride, RideChoice.UNTIL).tour
        val lineKey = "10:460.045:110.000"
        // the app before 2026-11-21: the key had no ride tag and the picture showed the whole line
        val old = ThumbRules.sig(t) + "|" + lineKey
        val plan = ThumbRules.plan(listOf(t), { old }, { lineKey })
        assertEquals("a new picture, not just a new key on the old one", listOf(1L), plan.make.map { it.first.id })
        assertTrue(plan.rekey.isEmpty())
        val made = plan.make.single().second
        assertTrue("once: its new key needs nothing more", ThumbRules.plan(listOf(t), { made }, { lineKey }).let { it.make.isEmpty() && it.rekey.isEmpty() && it.queried == 0 })
    }

    @Test fun aPictureFromBeforeTheUpdateOfAnUntouchedTourStays() {
        val asRecorded = savedAsRecorded().tour
        val noRecord = asRecorded.copy(rides = null)
        val noPoints = noRecord.copy(id = 2, uuid = "u2")
        val old = mapOf("u1" to ThumbRules.sig(asRecorded) + "|10:460.045:110.000", "u2" to ThumbRules.sig(noPoints) + "|none")
        for (tours in listOf(listOf(asRecorded), listOf(noRecord, noPoints))) {
            val plan = ThumbRules.plan(tours, { old[it] }, { error("no database work for an unchanged tour") })
            assertTrue("no redraw, no key read", plan.make.isEmpty() && plan.rekey.isEmpty() && plan.queried == 0)
        }
    }

    @Test fun aGondolaAtTheStartLeavesNoLonePointInTheDrawnLine() {
        val db = saved(Ride(0, 4, RideKind.LIFT), RideChoice.NOT_COUNTED)
        assertEquals("the valley station stays a kept point of its own", listOf(1, 6),
            com.onpath.app.rides.RideView.keptSegments(listOf(pts), db.tour.rides).map { it.size })
        val line = RoutesState(db).line(db.tour) { emptyList() }
        assertEquals("the line starts where the walk starts", listOf(6), line.map { it.size })
        assertEquals(pts[4].lat, line.single().first().lat, 1e-9)
    }

    @Test fun aTourThatIsOneRideStillHasItsLine() {
        val db = saved(Ride(0, 9, RideKind.VEHICLE), RideChoice.NOT_COUNTED)
        assertEquals("only lone points are kept: the whole track is drawn", listOf(10),
            RoutesState(db).line(db.tour) { emptyList() }.map { it.size })
    }
}
