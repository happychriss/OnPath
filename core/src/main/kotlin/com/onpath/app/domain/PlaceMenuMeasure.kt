package com.onpath.app.domain

import com.onpath.app.routing.GeoPoint

/**
 * The add rows of a held place's menu (POI, found pin) while PLANNING – 2+ points, no tour, no round trip (owner
 * 2026-11-17: „wenn ich da nicht einfügen muss, dann braucht das auch im Menü nicht angezeigt zu werden“, F-PLAN-15).
 * Decided exactly as a hold on the empty map: on the drawn line at once („Einfügen“ only); else appending and inserting
 * are measured on the paths ([HoldPaths]: off the main thread, at most its time limit, the straight lines on a timeout or
 * any failure, a newer measure or [HoldPaths.cancel] stops it), then [PlaceAddMenu.planningRows]. The menu opens only once
 * its rows are decided – nothing jumps. App only (the browser planner decides in its engine).
 */
object PlaceMenuMeasure {

    /** The decided rows: [menu], by [action] on the numbers [detours]; [how] = for the trace ("on the line …" / "paths: …"). */
    class Rows(val menu: PlaceAddMenu.Menu, val action: PlaceAddMenu.HoldAction, val detours: PlaceAddMenu.Detours, val how: String)

    /**
     * Decides the rows for the place [p] beside the plan [points] (2+): [lineDp] = its distance to the drawn line on screen,
     * [straight] = its straight-line numbers ([PlaceAddMenu.detours]). On the line [open] is called at once and this returns
     * false; else the measure starts on [paths] (true) and [open] comes on its scope with the outcome – never for a
     * measure cancelled or replaced.
     */
    fun start(paths: HoldMeasure, points: List<GeoPoint>, p: GeoPoint, lineDp: Double, straight: PlaceAddMenu.Detours,
              router: HoldMeasure.Router, open: (Rows) -> Unit): Boolean {
        val now = PlaceAddMenu.planningHold(lineDp, straight)
        if (now == PlaceAddMenu.HoldAction.ON_LINE) {
            open(Rows(PlaceAddMenu.planningRows(now, straight), now, straight, "on the line (${lineDp.toInt()} dp)"))
            return false
        }
        paths.start(points, p, straight, router) { o ->
            val a = PlaceAddMenu.planningHold(lineDp, o.detours)
            open(Rows(PlaceAddMenu.planningRows(a, o.detours), a, o.detours, "line ${lineDp.toInt()} dp, ${o.describe()}"))
        }
        return true
    }
}
