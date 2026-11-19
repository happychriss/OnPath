package com.onpath.web

import com.onpath.app.debug.Problems
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.SharedPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The page's slices through their exports (WebState.kt): the app's real RoutesState and SaveDialog over the store seam
 * (the page's stored plans loaded, the slice's writes drained). Time is passed in – nothing here reads a clock.
 */
class WebStateTest {
    private fun code(vararg p: Pair<Double, Double>) = PlanCode.encode(SharedPlan(p.map { GeoPoint(it.first, it.second) }, Activity.HIKE))
    private val a = code(46.0 to 11.0, 46.01 to 11.01)
    private val b = code(46.02 to 11.0, 46.03 to 11.0)
    private val c = code(46.04 to 11.0, 46.05 to 11.0)

    @Suppress("UNCHECKED_CAST") private fun obj(json: String) = WebJson.parse(json) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun list(json: String) = WebJson.parse(json) as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST") private fun Map<String, Any?>.o(key: String) = this[key] as Map<String, Any?>?
    @Suppress("UNCHECKED_CAST") private fun Map<String, Any?>.l(key: String) = this[key] as List<Map<String, Any?>>
    private fun state() = obj(routesStateJson())
    private fun writes() = list(routesStoreTakeWrites())
    private fun ids(rows: List<Map<String, Any?>>) = rows.map { "${it["kind"]}:${it["id"]}" }
    private fun place(kind: String, id: String) = mapOf("kind" to kind, "id" to id)

    /** A SavedPlan as sync.ts stores it (more fields than the slice reads) and a RecentPlan as recent.ts does. */
    private fun saved(id: String, title: String, plan: String, at: Long) =
        """{"id":"$id","kind":"plan","title":"$title","plan":"$plan","track":"","updatedAt":$at,"device":"d1","mine":true,"dirty":false,"onPhone":true,"favourite":true,"days":null}"""
    private fun draft(id: String, title: String, plan: String, at: Long, deletedAt: Long? = null) =
        """{"id":"$id","code":"$plan","title":"$title","points":"as the page wrote it","at":$at""" + (deletedAt?.let { ",\"deletedAt\":$it" } ?: "") + ""","later":{"x":[1,2.5,null,"é\n"]}}"""
    private fun load(saved: List<String> = emptyList(), drafts: List<String> = emptyList(), binned: List<String> = emptyList(), recent: String? = null) =
        routesStoreLoad("{\"saved\":$saved,\"drafts\":$drafts,\"binned\":$binned" + (recent?.let { ",\"recent\":$it" } ?: "") + "}")

    @Before fun fresh() { WebState.reset(); Problems.drain() }

    @Test fun loadThenTheStateJsonHasTheWholeShape() {
        assertEquals("""{"rows":2,"skipped":0}""", load(listOf(saved("s1", "Kofel", c, 100)), listOf(draft("r1", "Route 7 Nov", a, 200)), recent = """{"kind":"draft","id":"r1"}"""))
        assertEquals(
            """{"open":false,"tab":"PLANNED","sort":{"key":"NEWEST","biggestFirst":true},"justSaved":null,""" +
                """"recent":{"kind":"draft","id":"r1"},"row":{"live":false,"place":{"kind":"draft","id":"r1"},"saved":false},""" +
                """"tours":[{"kind":"draft","id":"r1","title":"Route 7 Nov","draft":true,"tourKind":"PLANNED","at":200,"deletedAt":null,"points":2},""" +
                """{"kind":"saved","id":"s1","title":"Kofel","draft":false,"tourKind":"PLANNED","at":100,"deletedAt":null,"points":2}],""" +
                """"bin":[],"closed":0,"pendingWrites":0}""",
            routesStateJson())
        assertEquals("a load writes nothing", "[]", routesStoreTakeWrites())
        assertTrue(Problems.drain().isEmpty())
    }

    /** RoutesFlowPropertyTest.theNextPlanReplacesTheOneUnderZuletztIntoTheRecycleBin, through the exports. */
    @Test fun theNextPlanReplacesTheOneUnderZuletzt_stateAndDrainedWrites() {
        load(listOf(saved("s1", "Kofel", c, 100)), listOf(draft("r1", "Route 7 Nov", a, 200)), recent = """{"kind":"draft","id":"r1"}""")
        assertEquals("""{"id":"r2"}""", routesKeepDraft("r2", b, "Route 8 Nov", "46.02,11;46.03,11", true, "", "", 5_000.0))
        val s = state()
        assertEquals(place("draft", "r2"), s.o("recent"))
        assertEquals(mapOf("live" to false, "place" to place("draft", "r2"), "saved" to false), s.o("row"))
        assertEquals("one draft outside the bin: the newest; the saved route untouched", listOf("draft:r2", "saved:s1"), ids(s.l("tours")))
        assertEquals("the replaced plan is in the bin, not gone", listOf("draft:r1"), ids(s.l("bin")))
        assertEquals("the page's time, not the system's", 5_000.0, s.l("bin")[0]["deletedAt"])
        assertEquals(3.0, s["pendingWrites"])
        // the writes, in the slice's order: the new draft, the old one into the bin, the place
        val w = writes()
        assertEquals(listOf("insert", "setDeleted", "recent"), w.map { it["op"] })
        assertEquals(mapOf("id" to "r2", "code" to b, "title" to "Route 8 Nov", "points" to "46.02,11;46.03,11", "at" to 5_000.0), w[0].o("row"))
        assertEquals("draft", w[1]["kind"]); assertEquals("r1", w[1]["id"]); assertEquals(5_000.0, w[1]["deletedAt"])
        // the page's own object comes back whole: the field changed, everything else – fields the engine does not know too – untouched
        assertEquals(obj(draft("r1", "Route 7 Nov", a, 200, deletedAt = 5_000)), w[1].o("row"))
        assertEquals(place("draft", "r2"), w[2].o("place"))
        assertEquals("taken once", "[]", routesStoreTakeWrites())
        assertEquals(0.0, state()["pendingWrites"])
    }

    @Test fun aLoadWhileWritesWaitIsRefusedAndLosesNothing() {
        load(drafts = listOf(draft("r1", "A", a, 200)))
        routesToBin("draft", "r1", 7_000.0)
        val refused = obj(load(drafts = listOf(draft("r1", "A", a, 200))))
        assertTrue(refused["error"].toString(), refused["error"].toString().contains("1 write(s) wait"))
        assertEquals(1.0, refused["pending"])
        assertEquals("the slice still sees its write", listOf("draft:r1"), ids(state().l("bin")))
        assertEquals(listOf("setDeleted"), writes().map { it["op"] })
        // drained (the page applied it): the page's storage is the truth again
        assertEquals("""{"rows":1,"skipped":0}""", load(drafts = listOf(draft("r1", "A", a, 200, deletedAt = 7_000))))
        assertEquals(listOf("draft:r1"), ids(state().l("bin")))
    }

    @Test fun malformedInputIsAnErrorJsonAndAProblemNeverAnException() {
        load(drafts = listOf(draft("r1", "A", a, 200)))
        for (bad in listOf("", "{", "[1,2]", "nonsense", "{\"saved\":", "\"text\"", "{\"saved\":[{\"id\":\"x\",\"plan\":")) {
            val r = obj(routesStoreLoad(bad))
            assertTrue("$bad → $r", r["error"].toString().startsWith("routesStoreLoad"))
        }
        assertEquals("the list stays as it was", listOf("draft:r1"), ids(state().l("tours")))
        assertTrue("reported", Problems.drain().any { it.contains("routes") })
        // rows that cannot be read are left out (reported), wrong types are no rows, a code that is no plan gives a row without points
        assertEquals("""{"rows":2,"skipped":3}""", routesStoreLoad(
            """{"saved":[{"title":"no id"},7,{"id":"s9","kind":"plan","title":"T","plan":"!!!","updatedAt":"yesterday"},{"id":"s9"}],"drafts":{"not":"a list"},"binned":[{"id":"t1","kind":"track","title":"GPX","plan":"","track":"x","at":5,"deletedAt":9}]}"""))
        val s = state()
        assertEquals(listOf("saved:s9"), ids(s.l("tours"))); assertEquals(0.0, s.l("tours")[0]["points"]); assertEquals(0.0, s.l("tours")[0]["at"])
        assertEquals("IMPORTED", s.l("bin").single()["tourKind"])
        assertEquals(4, Problems.drain().size)
        // wrong arguments: an error, nothing changed, nothing thrown
        for (r in listOf(routesTakePlace("folder", "x", 1.0), routesTab("NOPE"), routesSortTap(""), routesToBin("", "", Double.NaN),
            routesRestore("x", "y", 1.0), routesShowSaved("x", "y"), routesMarkSaved("x", "y"), routesStoreMoved("a", "b", "c", "d"),
            routesKeepDraft("", a, "t", "", true, "", "", 1.0), routesKeepDraft("r7", "no code", "t", "", true, "", "", 1.0),
            saveDialogRequestCopy("", "n", ""))) assertTrue(r, obj(r).containsKey("error"))
        // unknown rows: an answer, never an exception
        assertEquals("""{"ok":false}""", routesToBin("draft", "nobody", 1.0))
        assertEquals("""{"ok":false}""", routesRestore("saved", "nobody", 1.0))
        assertEquals("""{"ok":false}""", routesSaveDraft("nobody", 1.0))
        assertEquals("""{"ok":false}""", routesRenameDraft("nobody", "a", "b"))
        assertEquals("a name never seen", """{"ok":false}""", routesStoreMoved("draft", "never seen", "saved", "s"))
        assertEquals("""{"ok":false}""", saveDialogProposed(99, "x", "d", ""))
        assertEquals("""{"write":null}""", saveDialogConfirm("x", ""))
        assertEquals("null", routesRecentRow(0, "", "", "", false))
        assertEquals("[]", routesStoreTakeWrites())
    }

    /** RoutesFlowPropertyTest.aSavedRouteLeftAsItIsTakesThePlaceAndStaysSaved + the app's afterSaved, with the page's ids. */
    @Test fun aSavedRouteTakesThePlace_andASaveRemovesTheDraftItCameFrom() {
        load(listOf(saved("s1", "Kofel", c, 100)), listOf(draft("r1", "A", a, 200)))
        // the saved route leaves the map as it is: it takes the place, the draft waits in the bin
        assertEquals("""{"id":null}""", routesKeepDraft("r9", c, "Kofel", "", false, "saved", "s1", 3_000.0))
        var s = state()
        assertEquals(place("saved", "s1"), s.o("recent"))
        assertEquals(mapOf("live" to false, "place" to place("saved", "s1"), "saved" to true), s.o("row"))
        assertEquals(listOf("draft:r1"), ids(s.l("bin")))
        assertEquals(listOf("setDeleted", "recent"), writes().map { it["op"] })
        // the draft restored: it takes the place back (a saved route there stays a saved route)
        assertEquals("""{"ok":true}""", routesRestore("draft", "r1", 3_500.0))
        assertEquals(place("draft", "r1"), state().o("recent"))
        val back = writes()
        assertEquals(listOf("setDeleted", "recent"), back.map { it["op"] })
        assertNull(back[0]["deletedAt"]); assertFalse("out of the bin: the page's row has no deletedAt any more", back[0].o("row")!!.containsKey("deletedAt"))
        // the plan of that draft is saved by the page as s2 (the page stored it and loaded): the draft goes for good
        load(listOf(saved("s1", "Kofel", c, 100), saved("s2", "A", a, 4_000)), listOf(draft("r1", "A", a, 200)))
        assertEquals("""{"gone":1}""", routesAfterSaved("s2", "r1", a, 4_000.0))
        s = state()
        assertEquals(listOf("saved:s2", "saved:s1"), ids(s.l("tours"))); assertEquals(place("saved", "s2"), s.o("recent"))
        val w = writes()
        assertEquals(listOf("delete", "recent"), w.map { it["op"] })
        assertEquals("r1", w[0]["id"]); assertFalse("gone for good: no row to write back", w[0].containsKey("row"))
        // a plan on the map: the page's recentRow with `live`
        assertEquals("""{"live":true,"place":{"kind":"saved","id":"s2"},"saved":true}""", routesRecentRow(2, "saved", "s2", a, false))
        assertEquals("""{"live":true,"place":{"kind":"saved","id":"s2"},"saved":false}""", routesRecentRow(3, "saved", "s2", b, true))
        assertEquals("""{"live":true,"place":null,"saved":false}""", routesRecentRow(2, "", "", b, true))
    }

    @Test fun zuletztSpeichern_theDraftBecomesASavedRouteAndFollowsItsNewId() {
        load(drafts = listOf(draft("r1", "A", a, 200)), recent = """{"kind":"draft","id":"r1"}""")
        assertEquals("""{"ok":true}""", routesSaveDraft("r1", 6_000.0))
        var s = state()
        assertEquals(false, s.l("tours").single()["draft"]); assertEquals(place("draft", "r1"), s.o("justSaved"))
        val w = writes()
        assertEquals(listOf("setDraft", "recent"), w.map { it["op"] }); assertEquals(false, w[0]["draft"]); assertEquals(a, w[0].o("row")!!["code"])
        // the page stored it as the saved plan s7 and says so: place and mark follow, the page is told the place's new name
        assertEquals("""{"ok":true}""", routesStoreMoved("draft", "r1", "saved", "s7"))
        s = state()
        assertEquals(place("saved", "s7"), s.o("recent")); assertEquals(place("saved", "s7"), s.o("justSaved"))
        assertEquals(listOf(mapOf("op" to "recent", "place" to place("saved", "s7"))), writes())
        // … and its storage, loaded, is the same row: nothing twice, the place holds
        load(listOf(saved("s7", "A", a, 6_000)), recent = """{"kind":"saved","id":"s7"}""")
        s = state()
        assertEquals(listOf("saved:s7"), ids(s.l("tours"))); assertEquals(place("saved", "s7"), s.o("recent")); assertEquals(place("saved", "s7"), s.o("justSaved"))
        // closing My routes drops the mark and counts (RoutesState.onClosed)
        routesShowSaved("saved", "s7"); assertEquals(true, state()["open"]); assertEquals("DONE", state()["tab"])
        routesSortTap("LENGTH"); routesSortTap("LENGTH")
        assertEquals(mapOf("key" to "LENGTH", "biggestFirst" to false), state().o("sort"))
        routesOpen(false)
        s = state()
        assertEquals(false, s["open"]); assertNull(s["justSaved"]); assertEquals(1.0, s["closed"])
        routesOpen(true); routesTab("PLANNED")
        assertEquals("a sort holds while My routes is open, not across openings", mapOf("key" to "NEWEST", "biggestFirst" to true), state().o("sort"))
    }

    @Test fun trimRenameAndARestoredSavedRoutesTwin() {
        load(drafts = listOf(draft("r1", "old", a, 100), draft("r2", "Route 7 Nov", b, 300), draft("r3", "older", c, 200)))
        assertEquals("the drafts beyond the newest go to the bin", """{"gone":2}""", routesTrimDrafts(8_000.0))
        assertEquals(listOf("draft:r2"), ids(state().l("tours")))
        assertEquals("idempotent", """{"gone":0}""", routesTrimDrafts(8_001.0))
        // the place name comes for the draft kept under its date name – only while it is still that
        assertEquals("""{"ok":true}""", routesRenameDraft("r2", "Route 7 Nov", " Algund → Meran "))
        assertEquals("Algund → Meran", state().l("tours").single()["title"])
        assertEquals("""{"ok":false}""", routesRenameDraft("r2", "Route 7 Nov", "Other"))
        val w = writes()
        assertEquals(listOf("setDeleted", "setDeleted", "rename"), w.map { it["op"] })
        assertEquals("Algund → Meran", w[2]["title"]); assertEquals("Algund → Meran", w[2].o("row")!!["title"])
        // a saved route with the draft's points comes back from the bin: the draft gives way, the saved route takes its place
        routesTakePlace("draft", "r2", 8_500.0); writes()
        load(drafts = listOf(draft("r2", "Algund → Meran", b, 300)),
            binned = listOf("""{"id":"s4","kind":"plan","title":"Meran","plan":"$b","track":"","favourite":false,"routing":null,"routingData":null,"at":50,"deletedAt":60,"folder":"none"}"""))
        assertEquals("""{"ok":true}""", routesRestore("saved", "s4", 9_000.0))
        val s = state()
        assertEquals(listOf("saved:s4"), ids(s.l("tours"))); assertEquals(listOf("draft:r2"), ids(s.l("bin"))); assertEquals(place("saved", "s4"), s.o("recent"))
        val r = writes()
        assertEquals(listOf("setDeleted saved:s4", "setDeleted draft:r2", "recent null:null"), r.map { "${it["op"]} ${it["kind"]}:${it["id"]}" })
        assertEquals("the BinnedRoute comes back whole, without deletedAt", "none", r[0].o("row")!!["folder"])
    }

    /** SaveFlowTest's dialog behaviours (the proposal that comes later, a typed name, overwrite, copy) through the exports. */
    @Test fun theSaveDialog() {
        val dated = "Route 31 Oct, 10:12"; val proposal = "Vellau → Leiteralm"
        assertEquals("""{"open":false,"ask":null}""", saveDialogJson())
        // a new plan: the date name meanwhile, then the proposal – made free among the names taken
        assertEquals("""{"token":1}""", saveDialogRequest(false, "", "", dated, proposal))
        assertEquals("""{"open":true,"ask":{"kind":"NEW","name":"Route 31 Oct, 10:12","routeId":null,"token":1}}""", saveDialogJson())
        assertEquals("""{"ok":true}""", saveDialogProposed(1, proposal, dated, proposal))
        assertEquals("""{"open":true,"ask":{"kind":"NEW","name":"Vellau → Leiteralm (2)","routeId":null,"token":1}}""", saveDialogJson())
        assertEquals("""{"write":{"kind":"NEW","name":"Leiteralm loop","routeId":null}}""", saveDialogConfirm("  Leiteralm loop ", ""))
        assertEquals("""{"open":false,"ask":null}""", saveDialogJson())
        // a proposal arriving after Cancel, or for an older dialog, changes nothing and opens nothing
        saveDialogRequest(false, "", "", dated, ""); saveDialogCancel()
        assertEquals("""{"ok":false}""", saveDialogProposed(2, proposal, dated, ""))
        assertEquals("""{"open":false,"ask":null}""", saveDialogJson())
        saveDialogRequest(false, "", "", dated, "")
        assertEquals("""{"ok":false}""", saveDialogProposed(2, proposal, dated, ""))
        assertEquals(dated, obj(saveDialogJson()).o("ask")!!["name"])
        assertEquals("a blank name keeps the proposal", """{"write":{"kind":"NEW","name":"Route 31 Oct, 10:12","routeId":null}}""", saveDialogConfirm("  ", ""))
        // a saved route: its name, and Save overwrites it – renamed when the name changed, still one route
        saveDialogRequest(true, proposal, "s1", dated, proposal)
        assertEquals("""{"open":true,"ask":{"kind":"OVERWRITE","name":"Vellau → Leiteralm","routeId":"s1","token":4}}""", saveDialogJson())
        assertEquals("""{"write":{"kind":"OVERWRITE","name":"Leiteralm","routeId":"s1"}}""", saveDialogConfirm("Leiteralm", "s1"))
        // … unless the plan is linked to another route by now: a new route
        saveDialogRequest(true, proposal, "s1", dated, "")
        assertEquals("""{"write":{"kind":"NEW","name":"Vellau → Leiteralm","routeId":null}}""", saveDialogConfirm("", "s2"))
        // ⋮ → Save as copy
        saveDialogRequestCopy("s1", proposal, proposal)
        assertEquals("COPY", obj(saveDialogJson()).o("ask")!!["kind"]); assertEquals("$proposal (2)", obj(saveDialogJson()).o("ask")!!["name"])
        assertEquals("""{"write":{"kind":"COPY","name":"Vellau → Leiteralm (2)","routeId":"s1"}}""", saveDialogConfirm("", ""))
        assertTrue(Problems.drain().isEmpty())
    }

    @Test fun theJsonWriterGivesBackWhatTheReaderRead() {
        val text = """{"a":1,"b":-2.5,"c":"é \"q\" \\ \n\t","d":[true,false,null,{"e":1731900000000}],"f":{}}"""
        assertEquals(text, WebJson.write(WebJson.parse(text)))
        assertEquals("\"\\u0001\"", WebJson.str("\u0001"))
    }
}
