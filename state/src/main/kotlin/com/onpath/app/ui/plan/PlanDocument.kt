package com.onpath.app.ui.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.onpath.app.domain.PlanGuidance
import com.onpath.app.domain.PlanHistory
import com.onpath.app.domain.RouteRules
import com.onpath.app.domain.plan.PlanSession
import com.onpath.app.routing.GeoPoint

/**
 * Where the plan on the map is kept between two runs of the app (the preferences in the app, a map in tests): its
 * points, the pinned ones, and the saved route it was last saved or loaded as.
 */
interface PlanStore {
    var points: List<GeoPoint>
    var pinned: Set<GeoPoint>
    var savedName: String?
    var savedId: Long
    var savedPoints: List<GeoPoint>?
}

/**
 * The plan on the map as one object: its points as the screens observe them ([points]), Undo / Redo
 * and the pinned points ([PlanSession], with [canUndo] / [canRedo] as state), the saved route it belongs to
 * ([savedName], [savedId], [unsaved], [setSaved]) – and that all of it survives the process: every change of the
 * points, the pins and the saved link is written to [store] here, at once, and read back when the next run makes its
 * document. The route line is not here (domain/plan/RouteRun), nor what an edit means for a running tour, the map or
 * the routing – the view model does that around [edit] / [undo] / [redo]. Testable on the JVM (PlanDocumentTest).
 */
class PlanDocument(
    private val store: PlanStore,
    /**
     * The Undo / Redo steps: a fresh one in the app (a new run starts without history); the planner hands in the
     * history it keeps outside (it must survive a fresh routing worker) – its steps show at once ([canUndo]).
     */
    history: PlanHistory = PlanHistory(),
) {

    /** The plan's points, in order – Compose state: the map, the sheet and My routes' live row follow it. */
    val points: SnapshotStateList<GeoPoint> = mutableStateListOf<GeoPoint>().apply { addAll(store.points) }
    private val session = PlanSession(points, store.pinned, history)

    // ---------------- the points and their history ----------------

    var canUndo by mutableStateOf(session.canUndo)
        private set
    var canRedo by mutableStateOf(session.canRedo)
        private set
    private fun history() { canUndo = session.canUndo; canRedo = session.canRedo }
    private fun stored() { store.points = points.toList() }

    /** One undoable change: records the step, applies [block], stores the points; returns the points before it. */
    fun edit(block: MutableList<GeoPoint>.() -> Unit): List<GeoPoint> = session.edit(block).also { history(); stored() }

    /** The plan Undo brings back (taken off the undo stack, not applied yet – [replace]); null = nothing to undo. */
    fun undo(): List<GeoPoint>? = session.undo().also { history() }

    /** The plan Redo brings back (like [undo]); null = nothing to redo. */
    fun redo(): List<GeoPoint>? = session.redo().also { history() }

    /** Puts [list] in place of the points without an Undo step (Undo / Redo, rules that rewrite the same step). */
    fun replace(list: List<GeoPoint>) { session.replace(list); stored() }

    /** The plan empties (the one-point rule found nothing to plan): no step of its own. */
    fun empty() { session.empty(); stored() }

    /** The plan becomes position → the one point left (like "Hierhin"), in the same Undo step. */
    fun fromHere(position: GeoPoint, goal: GeoPoint) { session.fromHere(position, goal); stored() }

    /**
     * The router put the points [routed] onto their ways ([snapped], same size): the plan's points move there – no Undo
     * step, the marker, the stored plan and the route line agree. Undo / Redo follow with [snappedHistory].
     */
    fun moveSnapped(routed: List<GeoPoint>, snapped: List<GeoPoint>) {
        for (i in snapped.indices) if (snapped[i] != routed[i]) points[i] = snapped[i]
        stored()
    }

    /** Undo / Redo show the snapped points too ([moves] old → new). */
    fun snappedHistory(moves: Map<GeoPoint, GeoPoint>) { session.snapped(moves); onSnapped?.invoke(moves) }

    /** Multi-day tours (ui/plan/MultiDaySlice): the night marks are kept by value and follow a snap ([snappedHistory]). */
    var onSnapped: ((Map<GeoPoint, GeoPoint>) -> Unit)? = null
    /** Multi-day tours: the plan belongs to another saved route now ([setSaved]; -1 = none). */
    var onLinked: ((Long) -> Unit)? = null

    /**
     * Undo / Redo start afresh – a multi-day tour's day becomes the plan, or the whole tour again: an Undo across that
     * would put the other plan's points into this one.
     */
    fun forgetHistory() { session.forgetHistory(); history() }

    /** Every point reachable by Undo or Redo. */
    fun historyPoints(): Set<GeoPoint> = session.historyPoints()

    /** The start the one-point rule put in during a tour ([PlanSession.oneLeftStart]); null = none. */
    val oneLeftStart: GeoPoint? get() = session.oneLeftStart

    /** The one point left is where I stand, or only the start the one-point rule put in ([PlanSession.leftIsHere]). */
    fun leftIsHere(position: GeoPoint?): Boolean = session.leftIsHere(position)

    /** What an edit during a tour that left one point does ([PlanSession.oneLeft]). */
    fun oneLeft(pointsBefore: Int, recording: Boolean, following: Boolean, followIsTrack: Boolean, routeBackAsk: Boolean,
                position: GeoPoint?): PlanGuidance.OneLeft =
        session.oneLeft(pointsBefore, recording, following, followIsTrack, routeBackAsk, position)

    // ---------------- pinned points ----------------

    /** Points added by tapping a POI or a place, kept by value ([PlanSession.pinned]); routed exactly. */
    val pinned: Set<GeoPoint> get() = session.pinned

    fun pinnedIndices(): Set<Int> = session.pinnedIndices()

    /** [p] is passed exactly from now on (stored). */
    fun pin(p: GeoPoint) = pin(listOf(p))

    fun pin(all: Collection<GeoPoint>) { session.pinned += all; store.pinned = session.pinned }

    /** Forget pinned marks of points neither planned nor reachable by Undo / Redo (stored when some went). */
    fun prunePinned() { if (session.prunePinned()) store.pinned = session.pinned }

    // ---------------- the saved route the plan belongs to ----------------

    /** Name, tour id and points the plan was last saved or loaded as; the plan is unsaved when its points differ. */
    var linkName by mutableStateOf(store.savedName)
        private set
    var linkId = store.savedId
        private set
    var savedPoints by mutableStateOf(store.savedPoints)
        private set

    /**
     * The plan still belongs to that saved route (RouteRules.linkedToSaved): after Clear or with every point replaced
     * it is a new plan – no saved name, Save asks for a new name; Undo back to the old points restores the link.
     */
    val linked: Boolean get() = RouteRules.linkedToSaved(points, savedPoints)
    /** Name the plan was saved / loaded as, while it still belongs to that route. */
    val savedName: String? get() = linkName.takeIf { linked }
    /** The saved route's tour id, while the plan still belongs to it; -1 = none. */
    val savedId: Long get() = if (linked) linkId else -1L
    /** The plan has a route worth keeping that differs from the last save / load (RouteRules.isUnsaved). */
    val unsaved: Boolean get() = RouteRules.isUnsaved(points, savedPoints)

    /** The plan was saved / loaded as the route [id] called [name] with [points] – or belongs to none (null, -1, null). */
    fun setSaved(name: String?, id: Long, points: List<GeoPoint>?) {
        linkName = name; linkId = id; savedPoints = points
        store.savedName = name; store.savedId = id; store.savedPoints = points
        onLinked?.invoke(id)
    }
}
