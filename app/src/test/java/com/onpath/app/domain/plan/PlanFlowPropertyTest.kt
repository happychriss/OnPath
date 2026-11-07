package com.onpath.app.domain.plan

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanEdits
import com.onpath.app.ui.PlanAutoSave
import com.onpath.app.ui.PlanGuidance
import com.onpath.app.ui.RouteRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Random but seeded plan flows against [PlanSession] (test audit 2026-11-06, audit package 4): every operation the
 * user can do to a plan – add, insert, delete, move, reverse, round trip on/off, Undo, Redo, clear, load a saved route,
 * save, start / end a tour, the router snapping points – in any order, and after every step the rules that must always
 * hold:
 * 1. the plan can always be emptied (and one Undo brings it back);
 * 2. a saved plan is never lost or changed by editing – only "Los geht's" with a loaded saved plan changed since
 *    updates it in place (owner 2026-11-07), and then its version before is kept under Zuletzt;
 * 3. Undo right after an edit brings back exactly the points before it (and Redo the points after it);
 * 4. a tour never follows a plan other than the one shown (the guidance rules of [PlanGuidance]);
 * 5. nothing calculates forever: the newest route request always resolves, an older result is never applied.
 * A failing flow prints its seed and steps, so it can be replayed.
 */
class PlanFlowPropertyTest {

    private enum class Op { ADD, ADD_HERE, INSERT, DELETE, MOVE, REVERSE, ROUND_TRIP, UNDO, REDO, CLEAR, LOAD, SAVE, TOUR_START, TOUR_END, SNAP }

    /** A tiny stand-in for the view model: the plan, its stored routes, the tour's followed plan and the router. */
    private class Flow(seed: Int) {
        val rnd = Random(seed)
        val session = PlanSession(mutableListOf())
        val store = LinkedHashMap<String, List<GeoPoint>>()
        val storeShadow = LinkedHashMap<String, List<GeoPoint>>()
        /** The saved route the plan on the map belongs to (loaded or saved), null = none. */
        var linked: String? = null
        /** Zuletzt: versions a tour start replaced (never lost). */
        val drafts = ArrayList<List<GeoPoint>>()
        var overwrites = 0
        var recording = false
        var followed: List<GeoPoint>? = null
        var endedByEmpty = false
        val position = GeoPoint(51.1650, 6.9350)
        val requests = RouteRequests()
        var pending: Pair<Int, List<GeoPoint>>? = null
        var line: List<GeoPoint>? = null
        val steps = ArrayList<String>()
        var fromHere = 0
        var emptied = 0

        val points get() = session.points

        fun point() = GeoPoint(51.16 + rnd.nextDouble() * 0.02, 6.92 + rnd.nextDouble() * 0.03)

        /** What the view model does after every change of the points: guidance rules, then a new route request. */
        fun afterChange() {
            if (PlanGuidance.endsGuidance(points.size, recording, followed != null, false)) {
                followed = null; endedByEmpty = true
            }
            requests.invalidate()
            pending = null
            if (points.size >= 2) {
                val g = requests.next()
                pending = g to points.toList()
            } else line = null
        }

        /** The stub router answers: the newest request wins; an old answer arriving late must be dropped. */
        fun route() {
            val p = pending ?: return
            // a stale answer first (a slow old request): never applied
            val stale = p.first - 1
            if (stale > 0) check(!requests.isCurrent(stale)) { "an older request is still current" }
            if (requests.isCurrent(p.first)) {
                line = p.second
                pending = null
                // the view model's followEditedPlan: a tour that follows the plan follows the new route; a guidance
                // ended by emptying the plan comes back when points are back (PlanGuidance.resumes)
                if (PlanGuidance.resumes(endedByEmpty, recording, followed != null, p.second.size)) { endedByEmpty = false; followed = p.second }
                else if (recording && followed != null) followed = p.second
            }
        }

        /** One undoable edit as the view model does it, with the one-point rule during a tour. */
        fun edit(what: String, block: MutableList<GeoPoint>.() -> Unit) {
            val before = session.edit(block)
            val after = points.toList()
            if (recording && followed != null && points.size == 1) {
                when (session.oneLeft(before.size, recording, true, false, false, position)) {
                    PlanGuidance.OneLeft.EMPTY -> { session.empty(); emptied++ }
                    PlanGuidance.OneLeft.FROM_HERE -> { session.fromHere(position, points.single()); fromHere++ }
                    PlanGuidance.OneLeft.NONE -> Unit
                }
            }
            if (session.prunePinned()) Unit
            afterChange()
            steps += "$what (${before.size} → ${points.size})"
            // rule 3: Undo right after the edit brings back the points before it, Redo the points after it
            if (rnd.nextInt(4) == 0) {
                val now = points.toList()
                val back = session.undo()
                assertEquals("Undo after \"$what\" brings back the points before it", before, back)
                session.replace(back!!)
                val again = session.redo()
                assertEquals("Redo after that Undo brings back the edit's result", now, again)
                session.replace(again!!)
                afterChange()
                if (now != after) Unit // the one-point rule rewrote the same step – Undo still goes to [before]
            }
        }

        /**
         * Undo / Redo as the view model's step(): the one-point rule may build "position → point"; it never empties –
         * one point right here stays, and the guidance ends as for an empty plan (the old line is not followed).
         */
        fun undoRedo(what: String, prev: List<GeoPoint>) {
            val before = points.toList()
            session.replace(prev)
            if (recording && followed != null && points.size == 1)
                when (session.oneLeft(before.size, recording, true, false, false, position)) {
                    PlanGuidance.OneLeft.FROM_HERE -> session.fromHere(position, points.single())
                    PlanGuidance.OneLeft.EMPTY -> { followed = null; endedByEmpty = true }
                    PlanGuidance.OneLeft.NONE -> Unit
                }
            afterChange()
            steps += "$what (${before.size} → ${points.size})"
        }

        fun step(op: Op) {
            val n = points.size
            when (op) {
                Op.ADD -> edit("add") { add(point()) }
                // a point right where I stand (≤ 50 m, never [position] itself): the one-point rule's "here" case
                Op.ADD_HERE -> edit("add here") { add(GeoPoint(position.lat + 0.0001 + rnd.nextDouble() * 0.0002, position.lon + 0.0001)) }
                Op.INSERT -> if (n >= 2) { val p = point(); edit("insert") { add(PlanEdits.bestViaIndex(this, p), p) } }
                Op.DELETE -> if (n >= 1) { val i = rnd.nextInt(n); edit("delete $i") { removeAt(i) } }
                Op.MOVE -> if (n >= 2) {
                    val from = rnd.nextInt(n); val to = rnd.nextInt(n)
                    val moved = PlanEdits.move(points, from, to)
                    edit("move $from→$to") { clear(); addAll(moved) }
                }
                Op.REVERSE -> if (PlanEdits.reverseChanges(points)) { val r = PlanEdits.reverse(points); edit("reverse") { clear(); addAll(r) } }
                Op.ROUND_TRIP -> if (n >= 2) {
                    val w = if (PlanEdits.isRoundTrip(points)) PlanEdits.unRoundTrip(points) else PlanEdits.roundTrip(points)
                    if (w != points.toList()) edit("round trip") { clear(); addAll(w) }
                }
                Op.UNDO -> session.undo()?.let { undoRedo("undo", it) }
                Op.REDO -> session.redo()?.let { undoRedo("redo", it) }
                Op.CLEAR -> if (n > 0) { edit("clear") { clear() }; linked = null }
                Op.LOAD -> store.entries.randomOrNull(rnd)?.let { (k, v) -> edit("load $k") { clear(); addAll(v) }; linked = k }
                Op.SAVE -> if (n >= 2) {
                    val k = "Plan ${store.size + 1}"; store[k] = points.toList(); storeShadow[k] = points.toList(); linked = k
                    steps += "save $k"
                }
                Op.TOUR_START -> if (!recording) {
                    // "Los geht's" with a loaded saved plan changed since: the route is updated in place, its version
                    // before goes under Zuletzt (owner 2026-11-07 – no "Name (2)")
                    val k = linked
                    val stored = k?.let { store[it] }
                    if (k != null && stored != null && n >= 2 && stored != points.toList() &&
                        PlanAutoSave.atStart(n, unsaved = true, changesASavedPlan = true) == PlanAutoSave.AtStart.SAVE_OVERWRITE) {
                        drafts += stored
                        store[k] = points.toList(); storeShadow[k] = points.toList(); overwrites++
                        steps += "tour start: \"$k\" updated, its version before under Zuletzt"
                    }
                    recording = true; endedByEmpty = false
                    followed = if (n >= 2) points.toList() else null
                    steps += "tour start"
                }
                Op.TOUR_END -> if (recording) { recording = false; followed = null; endedByEmpty = false; session.oneLeftStart = null; steps += "tour end" }
                Op.SNAP -> if (n >= 1) {
                    val i = rnd.nextInt(n); val old = points[i]
                    val moved = GeoPoint(old.lat + 0.00005, old.lon - 0.00005)
                    // the router snapped a point onto the way: no Undo step, Undo / Redo show it snapped too
                    session.snapped(mapOf(old to moved))
                    session.replace(points.map { if (it == old) moved else it })
                    afterChange()
                    steps += "snap $i"
                }
            }
            route()
            checkRules()
        }

        fun checkRules() {
            // rule 5: nothing calculates forever – the newest request resolved
            assertNull("a route request never resolved after ${steps.lastOrNull()}", pending)
            if (points.size >= 2) assertEquals("the line belongs to the plan shown", points.toList(), line)
            // rule 4: a tour follows the plan shown, or nothing – a 1-point plan has no route of its own, so nothing
            if (points.size >= 2) followed?.let { assertEquals("the tour follows the plan shown", points.toList(), it) }
            if (points.size == 1) assertNull("a 1-point plan is followed by nothing (old line after ${steps.lastOrNull()})", followed)
            if (points.isEmpty()) assertNull("an empty plan is followed by nothing", followed)
            // the one-point rule never leaves only the start it put in (field bug 2026-11-06 21:18: "kein Punkt 1 mehr")
            // (only while the rule's start is known: a plan saved with that start and loaded after the tour is the user's)
            session.oneLeftStart?.let {
                assertTrue("the plan is not only the start the one-point rule put in", points.toList() != listOf(it))
            }
            // rule 2: a saved plan is never lost or changed by editing (only a tour start updates it, the version before
            // then waits under Zuletzt)
            assertEquals("saved plans unchanged", storeShadow, store)
        }

        /** Rule 1: the plan can always be emptied, and one Undo brings it back. */
        fun checkClearable() {
            val now = points.toList()
            if (now.isEmpty()) return
            session.edit { clear() }
            assertTrue("the plan empties", points.isEmpty())
            val back = session.undo()
            assertEquals("one Undo brings the cleared plan back", now, back)
            session.replace(back!!)
            afterChange(); route()
        }
    }

    @Test
    fun randomPlanFlowsKeepTheRules() {
        val t0 = System.nanoTime()
        var flows = 0
        var fromHere = 0
        var emptied = 0
        var overwrites = 0
        for (seed in 1..300) {
            val f = Flow(seed)
            try {
                repeat(120) { i ->
                    f.step(Op.entries[f.rnd.nextInt(Op.entries.size)])
                    if (i % 10 == 9) f.checkClearable()
                }
            } catch (e: Throwable) {
                throw AssertionError("seed $seed after: ${f.steps.takeLast(12).joinToString(" · ")}", e)
            }
            flows++
            fromHere += f.fromHere; emptied += f.emptied; overwrites += f.overwrites
            assertEquals("every version a tour start replaced is under Zuletzt", f.overwrites, f.drafts.size)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("300 flows of 120 steps in under 2 s (took $ms ms)", ms < 2_000)
        assertEquals(300, flows)
        // the flows really reach the one-point rule (both ways), not only plain edits
        assertTrue("one point left → position → point happened ($fromHere×)", fromHere > 0)
        assertTrue("one point left → the plan emptied happened ($emptied×)", emptied > 0)
        // and the tour start with a changed saved plan (owner 2026-11-07: update in place)
        assertTrue("a changed saved plan updated at the tour start ($overwrites×)", overwrites > 0)
    }

    @Test
    fun undoDuringATourBackToOnePointWhereIStandEndsTheGuidance() {
        // found by this test (audit package 4, 2026-11-07): plan [a point where I stand], add a far goal, start the tour,
        // Undo → one point left right here. Undo never empties a plan, so the point stays – but the tour must not keep
        // following the old 2-point line: it follows the plan shown or nothing (rule 4)
        val f = Flow(11)
        val here = GeoPoint(f.position.lat + 0.0002, f.position.lon + 0.0001)
        val goal = GeoPoint(51.18, 6.95)
        f.session.replace(listOf(here)); f.afterChange(); f.route()
        f.edit("add goal") { add(goal) }; f.route()
        f.step(Op.TOUR_START)
        assertEquals(listOf(here, goal), f.followed)
        f.step(Op.UNDO)
        assertEquals("Undo keeps the one point (never empties)", listOf(here), f.points.toList())
        assertNull("the tour no longer follows the old line", f.followed)
        f.step(Op.REDO)
        assertEquals("Redo brings the goal back and the tour follows it again", f.points.toList(), f.followed)
    }

    @Test
    fun deletingTheGoalOfTheOnePointPlanEmptiesIt() {
        // field bug 2026-11-06 21:18 as a flow: a 2-point plan during a tour, delete one → position → goal; delete the
        // goal → the start the rule put in is no point of the user's – the plan empties, not "position → that start"
        val f = Flow(7)
        f.session.replace(listOf(GeoPoint(51.17, 6.93), GeoPoint(51.18, 6.95)))
        f.afterChange(); f.route()
        f.step(Op.TOUR_START)
        f.edit("delete 0") { removeAt(0) }
        assertEquals(2, f.points.size)
        assertEquals(f.position, f.points.first())
        f.route()
        f.edit("delete goal") { removeAt(1) }
        assertTrue("the plan empties", f.points.isEmpty())
        f.route(); f.checkRules()
    }
}
