package com.onpath.app.data

import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The JDBC tables of the unit tests ([com.onpath.app.testkit.JdbcTours]) write a saved plan with the SAME columns and the
 * same WHERE as [TourDb] – so a flow test that stores a route through them (`SaveFlowTest`) sees what the phone would store.
 * TourDb builds its statements with ContentValues, JdbcTours with SQL text, so the equality is pinned here on both sources:
 * the column lists of `insertTour` and `replacePlanned`, the guard of the overwrite, the star. A column added to TourDb
 * without JdbcTours fails this test.
 */
class TourDbMirrorTest {
    private val tourDb = sourceFile("data/TourDb.kt").readText()
    private val jdbc = File("../core/src/testkit/kotlin/com/onpath/app/testkit/JdbcTours.kt").readText()

    private fun columns(body: String) = Regex("""put(?:Null)?\("(\w+)"""").findAll(body).map { it.groupValues[1] }.toSet()
    private fun body(from: String, to: String) = tourDb.substringAfter(from).substringBefore(to)

    @Test fun insertTourWritesTheSameColumns() {
        val app = columns(body("    fun insertTour(", "    /** My routes: everything not in the recycle bin.")) - "rides" // rides: recordings only
        val sql = Regex("\\w+").findAll(jdbc.substringAfter("INSERT INTO tours(").substringBefore(") VALUES")).map { it.value }.toSet() // the string is split over two lines
        assertEquals(app, sql)
    }

    @Test fun replacePlannedWritesTheSameColumnsUnderTheSameGuard() {
        val app = columns(body("    fun replacePlanned(", "    fun replaceImported(")) - "tour_id"
        val set = jdbc.substringAfter("fun replacePlanned(").substringAfter("UPDATE tours SET ").substringBefore("\" +").split(",").map { it.substringBefore("=").trim() }.toSet()
        val withDays = set + "days" // appended by the days / clearDays branch
        assertEquals(app, withDays)
        assertTrue(tourDb.contains("\"id=? AND kind=? AND deleted_at IS NULL\", arrayOf(id.toString(), TourKind.PLANNED.name)"))
        assertTrue(jdbc.contains("\" WHERE id=? AND kind=? AND deleted_at IS NULL\""))
    }

    @Test fun theStarIsTheOneColumnOfTheTourRow() {
        assertEquals(setOf("favourite"), columns(body("    fun setFavourite(", "    /** Moves a tour to the recycle bin")))
        assertTrue(jdbc.contains("UPDATE tours SET favourite=? WHERE id=?"))
    }
}
