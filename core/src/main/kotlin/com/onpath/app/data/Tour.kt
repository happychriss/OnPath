package com.onpath.app.data

import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.rides.RideRecord
import com.onpath.app.routing.Anchors
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.routes_kind_imported
import com.onpath.strings.routes_kind_planned
import com.onpath.strings.routes_kind_recorded
import org.jetbrains.compose.resources.StringResource

enum class TourKind(private val labelRes: StringResource) {
    PLANNED(Res.string.routes_kind_planned), RECORDED(Res.string.routes_kind_recorded), IMPORTED(Res.string.routes_kind_imported);

    /** "Planned route" / "Geplante Route" (strings_routes.xml); the stored form is the enum name. */
    val label: String get() = Txt.s(labelRes)
}

data class Tour(
    val id: Long,
    val kind: TourKind,
    val name: String,
    val createdMs: Long,
    val activity: String?,
    val sac: Int,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val durationS: Double,
    /** "done" or, for a recording in progress, "active". */
    val state: String,
    /** Planned: via points; imported: GPX waypoints. */
    val waypoints: List<GpxWaypoint>,
    /** Stable id across reinstalls and phones (backup file record, restore dedupe). */
    val uuid: String = "",
    /** Planned: indices of [waypoints] pinned on a POI. */
    val pinned: Set<Int> = emptySet(),
    /** In the recycle bin since (ms); null = in My routes. */
    val deletedMs: Long? = null,
    /** Recorded: bus / car / lift rides found and what the user chose (rides/); null = none, read as recorded. */
    val rides: RideRecord? = null,
    /** Planned: kept automatically under "Zuletzt" when it left the map (owner 2026-11-06), not saved by the user. */
    val draft: Boolean = false,
    /** Recorded: a copy of the plan it followed is stored ([TourPlan], `tour_plan`) – Gemacht offers Planen / Los geht's. */
    val hasPlan: Boolean = false,
    /**
     * Planned: the difficulty badge's level when it was saved (`RouteSummary.Difficulty` name: EASY / MODERATE /
     * DIFFICULT – distance + ascent raised by the trail technique, which needs the way details only the plan has); null =
     * not known (saved before schema 7, imported, recorded, restored from a backup). A hike's T level is [sac]. Kept for
     * sorting by difficulty later (owner 2026-11-12: "erst ohne, später dazu").
     */
    val difficulty: String? = null,
    /**
     * Planned / imported / recorded: starred in My routes (owner 2026-11-13, favourites variant C – the ☆ Favoriten filter
     * pill shows only these). Kept in the GPX backup's record (key "favourite") and, for plans and imported tracks, synced
     * with the browser (item field 9). Every existing tour reads false.
     */
    val favourite: Boolean = false,
    /**
     * Recorded: the plan it followed is a round trip (its points end at its start) – set when My routes reads the list
     * (ui/routes/RoundTrip); null = not known there (no plan: the row guesses from the line). Not stored.
     */
    val roundTrip: Boolean? = null,
    /**
     * Recorded: the average speed while moving (km/h) – the overview's „Ø Tempo“ (TourReview.averageFromTimes), set when
     * My routes reads the list from `tour-times.json`; null = not known there (the row shows its time instead). Not stored.
     */
    val avgKmh: Double? = null,
    /**
     * Planned: a multi-day tour's days (N, limits, night marks, description – docs/design/multi-day-tours.md stage 4);
     * null = a normal route (every tour saved before schema 9). Stored in `tours.days` (JSON), backed up, synced.
     */
    val days: MultiDay? = null,
    /**
     * Planned multi-day tour: map areas of its corridor are missing ("Karten fehlen" in My routes) – set when My routes
     * reads the list; null = not known / not a multi-day tour. Not stored.
     */
    val mapsMissing: Boolean? = null,
    /**
     * Planned: the hidden via points by index of [waypoints] (docs/design/detour-stitch.md §3); empty = none. As JSON:
     * [AnchorsJson]. Backed up and synced (tag 7 of the plan code).
     */
    val anchors: Map<Int, Anchors> = emptyMap(),
)
