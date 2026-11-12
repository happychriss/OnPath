package com.onpath.app.ui

import com.onpath.app.data.TourKind
import com.onpath.app.routing.GeoPoint

/** Which items of the ☰ menu can be used right now (disabled ones stay visible, greyed). */
data class RouteMenuState(
    val load: Boolean,
    val save: Boolean,
    val manage: Boolean = true,
    val startRecording: Boolean,
    val settings: Boolean = true,
    val updates: Boolean = true,
)

/**
 * What the plan sheet's primary button (and ☰ → Start recording) does when no tour runs – [AppViewModel.startNavigation]
 * and the button's words follow this one rule (owner 2026-10-30: without a line "Start navigation" was a lie).
 */
enum class PlanStart {
    /** A track with a line is shown (My routes / import): follow it ("Start navigation · …" in the track panel). */
    FOLLOW_TRACK,
    /** The plan has a line – routed or straight-line fallback ("Navigate anyway?" asks first): navigate it. */
    NAVIGATE_PLAN,
    /** Two or more points, the line is not there (calculating, cancelled, failed): the button waits (disabled). */
    WAIT_FOR_ROUTE,
    /**
     * No point or only one – or a shown track without a line (an empty recording, a GPX with one point): a plain
     * recording, nothing to follow – "Start recording · Hike".
     */
    RECORD_ONLY;

    companion object {
        /** [trackPoints] = points of the shown track, null = no track shown (the plan sheet). */
        fun of(trackPoints: Int?, hasRoute: Boolean, pointCount: Int): PlanStart = when {
            trackPoints != null -> if (trackPoints >= 2) FOLLOW_TRACK else RECORD_ONLY
            pointCount >= 2 && hasRoute -> NAVIGATE_PLAN
            pointCount >= 2 -> WAIT_FOR_ROUTE
            else -> RECORD_ONLY
        }
    }
}

/**
 * What Save's dialog leads to (owner 2026-10-31: Save always asks for the name): a new entry under the name in the
 * field (proposed by [RouteRules.newRouteName]), or overwrite the saved route the plan belongs to (renamed when the name
 * changed – still one route).
 */
enum class SaveMode { NEW_ROUTE, OVERWRITE }

/**
 * Save's name dialog while it is open: [name] prefilled (the proposal, the saved route's name, or "<name> (2)" for a
 * copy) and fully selected; a blank name keeps it. [routeId] = the route overwritten ([Kind.OVERWRITE]), else -1.
 * [then] runs after a successful save ("Replace current plan?" → Save first loads the other route then).
 */
data class SaveAsk(val kind: Kind, val name: String, val routeId: Long, val then: (() -> Unit)?) {
    enum class Kind { NEW, OVERWRITE, COPY }
}

/**
 * "Enabled when" rules of the menus and route management (docs/ux-two-modes.md "Menus").
 * Navigating = Navigate mode or a tour recording (also when looked at from Plan).
 */
object RouteRules {
    /**
     * @param hasRoute a planned route line exists
     * @param pointCount planned points (two or more = a route is coming, even while it is calculated)
     * @param savedCount saved routes / tours in My routes
     */
    fun menu(hasRoute: Boolean, pointCount: Int, savedCount: Int, navigating: Boolean, recording: Boolean) = RouteMenuState(
        load = savedCount > 0 && !navigating && !recording,
        save = hasRoute,
        startRecording = !hasRoute && pointCount < 2 && !recording && !navigating,
    )

    /**
     * Save (sheet button, ☰ "Save route", sheet ⋯ → Save route, "Save first"): when the plan was loaded from (or last
     * saved as) a planned route that still exists, it is overwritten (renamed when the dialog's name changed);
     * otherwise a new entry. Both ask for the name first (owner 2026-10-31); a copy is sheet ⋯ → "Save as copy".
     * @param savedId tour id the plan was last saved / loaded as (-1 = none)
     * @param plannedIds ids of the planned routes in My routes
     */
    fun saveMode(savedId: Long, savedName: String?, plannedIds: Collection<Long>): SaveMode =
        if (savedId >= 0 && savedName != null && savedId in plannedIds) SaveMode.OVERWRITE else SaveMode.NEW_ROUTE

    /**
     * Name proposed in Save's dialog for a new route: the [proposed] one – a plan link's title while
     * its points are unchanged, else the place proposal "Vellau → Leiteralm" (search/RouteNamer) – or, when there is
     * none, [dated] ("Route 31 Oct, 10:12"). A name a planned route already has ([taken]) gets " (2)", " (3)" … so two
     * entries in My routes never look the same; a copy of "Name" is proposed as "Name (2)".
     */
    fun newRouteName(proposed: String?, dated: String, taken: Collection<String>): String =
        com.onpath.app.routing.PlanNames.newRouteName(proposed, dated, taken)

    /**
     * Proposed name of Save as copy of [name]: "Name (2)" – the next free number, and a copy of "Name (2)" is
     * "Name (3)", not "Name (2) (2)".
     */
    fun copyName(name: String, taken: Collection<String>): String = com.onpath.app.routing.PlanNames.copyName(name, taken)

    /** ☰ → "Save route" as the top item (owner 2026-10-31, the sheet has no Save any more): whenever there is a plan. */
    fun saveInMenu(pointCount: Int): Boolean = pointCount >= 2

    /** A new name from My routes → Rename or Save's dialog: trimmed; blank keeps [old] (the proposal / old name). */
    fun cleanName(input: String, old: String): String = input.trim().ifBlank { old }

    /**
     * ✕ Close plan in the sheet's action row: something to close – also during a tour (owner): like deleting every point,
     * the route guidance ends and the recording goes on; Undo brings plan and guidance back (PlanGuidance).
     */
    fun canClear(pointCount: Int): Boolean = pointCount > 0

    /** Opening this item puts its points into the plan (else it is only shown as a track). */
    fun replacesPlan(kind: TourKind, waypointCount: Int): Boolean = kind == TourKind.PLANNED && waypointCount >= 2

    /**
     * What a tap on an item in My routes does ([AppViewModel.requestOpen]). While [navigating] (a tour) a planned route is
     * followed at once ([OpenAsk.FOLLOW_PLAN_DURING_TOUR]) and so is a done tour / track with a line ([hasLine],
     * [OpenAsk.FOLLOW_DURING_TOUR]) – no question, the recording goes on (owner 2026-11-06 22:05; was "Plan laden? Du bist
     * gerade unterwegs." with three answers and "Dieser Tour folgen?"). Without a tour tracks open beside the plan and a
     * plan opens at once ([OpenAsk.REPLACE_PLAN]: the plan on the map goes under Zuletzt).
     */
    fun openAsk(kind: TourKind, waypointCount: Int, navigating: Boolean, points: List<GeoPoint>, saved: List<GeoPoint>?,
                hasLine: Boolean = true): OpenAsk =
        when {
            !replacesPlan(kind, waypointCount) ->
                if (navigating && kind != TourKind.PLANNED && hasLine) OpenAsk.FOLLOW_DURING_TOUR else OpenAsk.NONE
            navigating -> OpenAsk.FOLLOW_PLAN_DURING_TOUR
            askBeforeLoading(kind, waypointCount, points, saved) -> OpenAsk.REPLACE_PLAN
            else -> OpenAsk.NONE
        }

    /**
     * The plan is worth keeping and differs from what was last saved or loaded ([saved], null = never).
     * One point is no route yet (a plan needs two points).
     */
    fun isUnsaved(points: List<GeoPoint>, saved: List<GeoPoint>?): Boolean =
        points.size >= 2 && (saved == null || points.size != saved.size ||
            points.indices.any { points[it].lat != saved[it].lat || points[it].lon != saved[it].lon }) // elevation does not count

    /**
     * The plan still belongs to the route it was last saved or loaded as ([saved], null = never): at least one of its
     * points is one of that route's points (lat/lon; owner). Real edits of a loaded route –
     * moving, adding, deleting some points – keep the link ("· changed, not saved", Save offers overwrite). After Clear,
     * or when every point was replaced (a new plan somewhere else), the link is gone: no saved name, Save asks for a new
     * name. The link itself is kept, so Undo back to the old points brings it back.
     */
    fun linkedToSaved(points: List<GeoPoint>, saved: List<GeoPoint>?): Boolean {
        if (saved.isNullOrEmpty()) return false
        val keys = saved.mapTo(HashSet()) { it.lat to it.lon }
        return points.any { (it.lat to it.lon) in keys }
    }

    /** Ask "Replace current plan?" before loading this item. */
    fun askBeforeLoading(kind: TourKind, waypointCount: Int, points: List<GeoPoint>, saved: List<GeoPoint>?): Boolean =
        replacesPlan(kind, waypointCount) && isUnsaved(points, saved)

    /**
     * A plan from a link (plan.onpath.earth, QR code, share, paste – A-005): during a tour it waits (the followed plan
     * stays), unsaved changes ask "Replace current route?" like loading a saved route, else it opens at once. The same
     * link opened twice never asks, and neither does a plan that is still exactly what an earlier link brought ([opened],
     * owner: scanning the next QR code replaces it silently; changed on the phone → asks).
     */
    fun linkOpen(navigating: Boolean, points: List<GeoPoint>, saved: List<GeoPoint>?, incoming: List<GeoPoint>,
                 opened: List<GeoPoint>? = null): LinkOpen =
        when {
            navigating -> LinkOpen.AFTER_TOUR
            isUnsaved(points, saved) && isUnsaved(points, incoming) && isUnsaved(points, opened) -> LinkOpen.ASK_FIRST
            else -> LinkOpen.NOW
        }
}

/** What a tap on an item of My routes does ([RouteRules.openAsk]); NONE = it opens at once. */
enum class OpenAsk { NONE, REPLACE_PLAN, FOLLOW_PLAN_DURING_TOUR, FOLLOW_DURING_TOUR }

/** "Neuer Plan – deine Aufnahme läuft weiter" on Navigate ([AppViewModel.newPlanNote]); [undo]: "Rückgängig" is offered. */
data class NewPlanNote(val undo: Boolean, val id: Long)

/** What happens with a plan from a link ([RouteRules.linkOpen]). */
enum class LinkOpen { AFTER_TOUR, ASK_FIRST, NOW }

/**
 * The plan at "Los geht's" (owner 2026-11-06: the tour and the plan are two things – the end of a tour never asks about
 * the plan; pure). A plan never saved is saved at the start; a saved plan changed since is updated in place.
 */
object PlanAutoSave {
    enum class AtStart { NOTHING, SAVE_NEW, SAVE_OVERWRITE }

    /**
     * "Los geht's" (owner 2026-11-06, like Komoot: what you ride is under Planned): [points] plan points; [unsaved] the plan
     * differs from what is stored; [changesASavedPlan] it belongs to a saved route; [fromDraft] it was loaded from
     * "Zuletzt". A plan from Zuletzt is saved (changed or not); a never-saved plan is saved; a saved plan changed since
     * updates that route in place like ☰ Plan speichern (owner 2026-11-07: no "Name (2)" copies) – its version before
     * goes under Zuletzt ([com.onpath.app.ui.routes.RoutesState.keepReplaced]); a saved plan as it is: nothing.
     */
    fun atStart(points: Int, unsaved: Boolean, changesASavedPlan: Boolean, fromDraft: Boolean = false): AtStart = when {
        points < 2 -> AtStart.NOTHING
        fromDraft -> AtStart.SAVE_NEW
        !unsaved -> AtStart.NOTHING
        changesASavedPlan -> AtStart.SAVE_OVERWRITE
        else -> AtStart.SAVE_NEW
    }
}

/**
 * "Zuletzt" in My routes (owner 2026-11-06: "Nicht gespeichert" goes – nothing is lost, nothing asks): a plan worth
 * keeping ([points] ≥ 2) that leaves the map and is not saved as it is ([unsaved]) is kept as a draft. Exactly [MAX] = one
 * plan stays under Zuletzt – the one worked on last, a draft or a saved route (owner 2026-11-12: "füllt sich schnell"); a
 * draft it replaces goes to the recycle bin (routes/RoutesState.keepDraft, LivePlanRows).
 */
object Drafts {
    const val MAX = 1
    fun keeps(points: Int, unsaved: Boolean): Boolean = points >= 2 && unsaved
}
