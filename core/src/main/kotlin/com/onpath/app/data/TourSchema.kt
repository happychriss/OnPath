package com.onpath.app.data

import com.onpath.app.routing.GeoPoint

/** The tour database's tables (kept apart so the migration is JVM tested: TourSchemaTest with sqlite-jdbc). */
object TourSchema {
    /**
     * 1, 2 = backup columns; 3 = `points.acc` (GPS accuracy); 4 = `tours.rides` (vehicle detection); 5 = `tours.draft`
     * (Zuletzt); 6 = the table `tour_plan` (the plan a recorded tour followed, [TourPlan]); 7 = `tours.difficulty`
     * ([Tour.difficulty]); 8 = `tours.favourite` ([Tour.favourite]); 9 = `tours.days` ([Tour.days], multi-day tours); 10 = `tours.anchors` and `tour_plan.anchors` (a plan's hidden
     * via points, [Tour.anchors] / [TourPlan.anchors]; existing rows NULL = none); 11 = `tour_plan.day` (the day of a
     * multi-day route a recorded tour rode, [TourPlan.day] → [Tour.planDay]; existing rows NULL = none).
     */
    const val VERSION = 11

    val CREATE = listOf(
        """CREATE TABLE tours(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            kind TEXT NOT NULL, name TEXT NOT NULL, created INTEGER NOT NULL,
            activity TEXT, sac INTEGER NOT NULL DEFAULT 3,
            distance REAL NOT NULL DEFAULT 0, ascent REAL NOT NULL DEFAULT 0,
            descent REAL NOT NULL DEFAULT 0, duration REAL NOT NULL DEFAULT 0,
            state TEXT NOT NULL DEFAULT 'done', waypoints TEXT,
            uuid TEXT, pinned TEXT, deleted_at INTEGER, rides TEXT, draft INTEGER NOT NULL DEFAULT 0, difficulty TEXT,
            favourite INTEGER NOT NULL DEFAULT 0, days TEXT, anchors TEXT)""",
        "CREATE INDEX tours_uuid ON tours(uuid)",
        """CREATE TABLE points(
            tour_id INTEGER NOT NULL, seq INTEGER NOT NULL, seg INTEGER NOT NULL,
            lat REAL NOT NULL, lon REAL NOT NULL, ele REAL, time INTEGER, acc REAL)""",
        "CREATE INDEX points_tour ON points(tour_id, seq)",
        CREATE_PLAN,
    )

    /** 2 → 3: GPS accuracy per recorded point (metres, null = unknown / imported). */
    const val ADD_ACC = "ALTER TABLE points ADD COLUMN acc REAL"

    /** 3 → 4: how to read the rides of a recorded tour (bus / car / lift); every point stays as it is. */
    const val ADD_RIDES = "ALTER TABLE tours ADD COLUMN rides TEXT"

    /** 4 → 5: plans kept under "Zuletzt" (owner 2026-11-06); every existing tour is saved (0). */
    const val ADD_DRAFT = "ALTER TABLE tours ADD COLUMN draft INTEGER NOT NULL DEFAULT 0"

    /** 6 → 7 (owner 2026-11-12): a planned route's difficulty at saving ([Tour.difficulty]); existing tours null. */
    const val ADD_DIFFICULTY = "ALTER TABLE tours ADD COLUMN difficulty TEXT"

    /** 7 → 8 (owner 2026-11-13): starred in My routes ([Tour.favourite]); every existing tour 0 (not starred). */
    const val ADD_FAVOURITE = "ALTER TABLE tours ADD COLUMN favourite INTEGER NOT NULL DEFAULT 0"

    /** 8 → 9 (multi-day tours, stage 4): a planned route's days ([Tour.days], JSON); existing tours null (a normal route). */
    const val ADD_DAYS = "ALTER TABLE tours ADD COLUMN days TEXT"

    /** 9 → 10 (detour stitch, docs/design/detour-stitch.md §3): a planned route's hidden via points ([Tour.anchors], JSON); existing tours null. */
    const val ADD_ANCHORS = "ALTER TABLE tours ADD COLUMN anchors TEXT"

    /** 9 → 10: the same for the plan copy of a recorded tour ([TourPlan.anchors]). */
    const val ADD_PLAN_ANCHORS = "ALTER TABLE tour_plan ADD COLUMN anchors TEXT"

    /**
     * 5 → 6 (owner 2026-11-07): the plan a recorded tour followed, one row per tour – a table of its own, because the line
     * can hold thousands of points and the tours list must stay light. A copy, not a reference: deleting or changing the
     * planned route never touches it; it goes with its tour ([PLAN_DELETE]).
     */
    const val CREATE_PLAN = """CREATE TABLE IF NOT EXISTS tour_plan(
            tour_id INTEGER PRIMARY KEY, name TEXT, activity TEXT NOT NULL, sac INTEGER NOT NULL,
            waypoints TEXT NOT NULL, pinned TEXT, line TEXT,
            distance REAL NOT NULL DEFAULT 0, ascent REAL NOT NULL DEFAULT 0, descent REAL NOT NULL DEFAULT 0,
            duration REAL NOT NULL DEFAULT 0, source_uuid TEXT, updated INTEGER NOT NULL, anchors TEXT, day INTEGER)"""

    /** The newest version of a tour's plan replaces the one before. Arguments: [planArgs]. */
    const val PLAN_UPSERT = """INSERT OR REPLACE INTO tour_plan(tour_id, name, activity, sac, waypoints, pinned, line,
            distance, ascent, descent, duration, source_uuid, updated, anchors, day) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""

    /** A tour removed for good: its plan copy goes too. Argument: the tour id. */
    const val PLAN_DELETE = "DELETE FROM tour_plan WHERE tour_id=?"

    /** `has_plan` (1 / 0) beside a tour row: My routes shows Planen / Los geht's on a recorded tour with a plan copy. */
    const val HAS_PLAN = "EXISTS(SELECT 1 FROM tour_plan p WHERE p.tour_id = tours.id) AS has_plan"

    /**
     * `plan_uuid` / `plan_day` beside a tour row ([Tour.planUuid], [Tour.planDay]): the multi-day route and day the recorded
     * tour rode, from its plan copy (schema 11) – both NULL unless the copy has a day.
     */
    const val PLAN_DAY = "(SELECT p.source_uuid FROM tour_plan p WHERE p.tour_id = tours.id AND p.day IS NOT NULL) AS plan_uuid, " +
        "(SELECT p.day FROM tour_plan p WHERE p.tour_id = tours.id AND p.source_uuid IS NOT NULL) AS plan_day"

    /** 10 → 11 (owner 2026-11-27): the day of a multi-day route the plan copy was ([TourPlan.day]); existing copies NULL. */
    const val ADD_PLAN_DAY = "ALTER TABLE tour_plan ADD COLUMN day INTEGER"

    fun planArgs(tourId: Long, p: TourPlan): Array<Any?> = arrayOf(tourId, p.name, p.activity, p.sac,
        TourJson.waypointsToJson(p.waypoints), TourJson.pinnedToJson(p.pinned), TourPlan.lineToJson(p.line),
        p.distanceM, p.ascentM, p.descentM, p.durationS, p.sourceUuid, p.updatedMs,
        AnchorsJson.writeRow(p.waypoints.map { GeoPoint(it.lat, it.lon) }, p.anchors, p.activity, p.sac), p.day?.takeIf { p.sourceUuid != null })

    /** Tables to add for an upgrade from [old] (each `IF NOT EXISTS`). */
    fun tablesFrom(old: Int): List<String> = buildList { if (old < 6) add(CREATE_PLAN) }

    class Step(val table: String, val column: String, val sql: String)

    /**
     * Columns to add for an upgrade from [old] (1 → 2 is done before in [TourDb.onUpgrade]), in order; the caller skips
     * a column already there. From 3 both are listed: some version-3 databases have `rides` but no `acc`, and without
     * `acc` every new point would fail to insert.
     */
    fun stepsFrom(old: Int): List<Step> = buildList {
        if (old < 4) add(Step("points", "acc", ADD_ACC))
        if (old < 4) add(Step("tours", "rides", ADD_RIDES))
        if (old < 5) add(Step("tours", "draft", ADD_DRAFT))
        if (old < 7) add(Step("tours", "difficulty", ADD_DIFFICULTY))
        if (old < 8) add(Step("tours", "favourite", ADD_FAVOURITE))
        if (old < 9) add(Step("tours", "days", ADD_DAYS))
        if (old < 10) add(Step("tours", "anchors", ADD_ANCHORS))
        // the caller creates missing tables first ([tablesFrom]) and skips a column already there: a version-5 database that kept
        // its `tour_plan` (a downgrade does) and one that just got it both end with the column
        if (old < 10) add(Step("tour_plan", "anchors", ADD_PLAN_ANCHORS))
        if (old < 11) add(Step("tour_plan", "day", ADD_PLAN_DAY))
    }
}
