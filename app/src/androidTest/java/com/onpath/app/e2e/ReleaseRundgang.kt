package com.onpath.app.e2e

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.onpath.app.data.Geo
import com.onpath.app.data.Prefs
import com.onpath.app.data.TourDb
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.NavPrompt
import com.onpath.app.navigation.NavState
import com.onpath.app.navigation.NavText
import com.onpath.app.plansync.PlanSync
import com.onpath.app.recording.DemoLocation
import com.onpath.app.recording.DemoPath
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.RouteSummary
import com.onpath.app.routing.SharedPlan
import com.onpath.app.sync.PairCode
import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncKeys
import com.onpath.app.sync.SyncLedger
import com.onpath.app.sync.SyncRound
import com.onpath.app.ui.AppMode
import com.onpath.app.ui.AppViewModel
import com.onpath.app.ui.RoutesTab
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.common_delete
import com.onpath.strings.common_ok
import com.onpath.strings.common_save
import com.onpath.strings.nav_arrived_title
import com.onpath.strings.nav_end_tour
import com.onpath.strings.nav_end_tour_title
import com.onpath.strings.plan_start_here
import com.onpath.strings.plan_undo
import com.onpath.strings.routes_end_tour_first
import com.onpath.strings.routes_tag_new
import com.onpath.strings.sync_pair
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Release-Rundgang (owner 2026-10-31, docs/release-rundgang.md): a fixed list of real user flows, played in the
 * emulator before every release with the demo location (never real GPS) and the real routing data (E10_N45.rd5 in
 * /data/local/tmp – tools/ci/release_rundgang.sh and e2e.yml push it). One test = one flow, run in name order; every
 * step has one assertion and a screenshot (/sdcard/e2e → the report). Rule: every field bug the owner finds becomes one
 * more flow here.
 *
 * Bike (touring) in Meran: Theatre → station, ~1.4 km, five junction cues and the amber "pushing · steps" hint (checked
 * on the JVM with the real profiles).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReleaseRundgang : E2EBase() {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = TourDb.get(ctx)
    private val started = System.currentTimeMillis()

    // ------------------------------------------------------------------------------------------------ flows

    /** 1 · Hold two points → the route appears with its difficulty badge and the hint line. */
    @Test fun flow01_planTwoPointsShowsRouteDifficultyAndHints() {
        launch { Prefs(ctx).activity = Activity.BIKE_TOURING; demoAt(THEATRE) }
        requireRealRouting()
        showOnMap(THEATRE, STATION)
        holdAt(THEATRE)
        clickText(Txt.s(Res.string.plan_start_here))
        waitUntil("hold → first point", 10_000) { waypointCount() == 1 }
        holdAt(STATION)
        waitUntil("hold → second point", 10_000) { waypointCount() == 2 }
        waitRouted()
        shot("1-route")
        val r = read { it.route!! }
        assertTrue("a route along the ways (${r.points.size} points)", r.points.size > 10)
        val badge = RouteSummary.rating(Activity.BIKE_TOURING, r.distanceM, r.ascentM, r.points).text
        assertNotNull("difficulty badge \"$badge\" shown", findFresh(By.text(badge), 10_000))
        val hint = read { it.warningLineText() }
        assertNotNull("this route has a hint (pushing / steps)", hint)
        assertNotNull("hint line \"$hint\" shown", findFresh(By.text(hint!!), 10_000))
        shot("2-difficulty-and-hint")
    }

    /** 2 · Start the tour at the start → a junction cue on the way → "You have arrived" → Save. */
    @Test fun flow02_tourAtTheStartJunctionCueThenArrival() {
        planned(THEATRE, STATION)
        val tourId = startTour()
        shot("1-navigate")
        rideRoute(factor = 5)
        waitUntil("a junction cue on the way", 180_000) { cues().isNotEmpty() }
        shot("2-after-junction-cue")
        waitUntil("the arrival question", 180_000) { read { it.arrivalAsk } }
        assertNotNull("\"You have arrived\" shown", findFresh(By.text(Txt.s(Res.string.nav_arrived_title)), 10_000))
        shot("3-arrived")
        clickText(Txt.s(Res.string.common_save))
        waitUntil("tour saved", 30_000) { RecordingServiceState.idle() && db.getTour(tourId)?.state == "done" }
        shot("4-saved")
    }

    /** 3a · Leave the route → the off-route question (FRAGE) → New route: the new line is followed. */
    @Test fun flow03a_offRouteQuestionNewRoute() {
        planned(THEATRE, STATION)
        startTour()
        val original = nav()!!.line
        leaveTheRoute(original)
        val q = waitQuestion()
        shot("1-off-route-question")
        clickText(NavText.TAKE)
        waitUntil("new route followed", 30_000) { nav()?.let { it.prompt == null && it.original != null && it.line != original } == true }
        shot("2-new-route")
        assertTrue("the new line starts near me", Geo.distance(nav()!!.line.first(), position()) < 150 + q.startOffM)
    }

    /** 3b · Leave the route → the off-route question → Keep original: the original stays, nothing re-routed. */
    @Test fun flow03b_offRouteQuestionKeepOriginal() {
        planned(THEATRE, STATION)
        startTour()
        val original = nav()!!.line
        leaveTheRoute(original)
        waitQuestion()
        shot("1-off-route-question")
        clickText(NavText.KEEP)
        waitUntil("original kept", 30_000) { nav()?.prompt == NavPrompt.Kept }
        assertEquals("the original line is still followed", original, nav()!!.line)
        shot("2-kept")
    }

    /**
     * 4 · Bug 02 (field 2026-10-31): start far from the route → Guide me → in Plan a hold near me becomes the new start
     * and the lead is redone → Undo → the lead is back exactly; no junction cue while far away.
     * Unit tests: FarStartTest.bug02*, WaypointInsertionTest.bug02*, FollowHistoryTest.bug02*, RoutingSpotTest.bug02*;
     * here the wiring: loadFollow → leadAgain, followAgain, wayThereFrom.
     */
    @Test fun flow04_farStartGuideMePlanEditUndoKeepsTheLead() {
        planned(THEATRE, STATION, at = FAR)
        startTour()
        waitUntil("far-start question", 60_000) { nav()?.prompt is NavPrompt.FarStart }
        shot("1-far-start")
        clickText(NavText.LEAD)
        waitUntil("led there (Guide me)", 90_000) { nav()?.let { it.session?.lead == true && it.session.rerouted && it.prompt == null } == true }
        val lead = nav()!!.line
        assertTrue("the lead starts here", Geo.distance(lead.first(), FAR) < 200)
        shot("2-led")
        // Plan: a hold between here and the start → the new point 1, the lead redone to it
        scenario.onActivity { vm(it).switchToPlan() }
        waitUntil("Plan during the tour", 10_000) { mode() == AppMode.PLAN }
        Thread.sleep(2_000) // Plan's own camera move first
        val near = GeoPoint(FAR.lat + (THEATRE.lat - FAR.lat) * 0.25, FAR.lon + (THEATRE.lon - FAR.lon) * 0.25)
        showOnMap(FAR, near)
        holdAt(near)
        waitUntil("hold → a point", 15_000) { waypointCount() == 3 }
        assertTrue("the held point is the new start", Geo.distance(read { it.waypoints[0] }, near) < 100)
        assertTrue("traced as the new start", traced("New point on the way there"))
        waitRouted()
        shot("3-new-start")
        scenario.onActivity { vm(it).continueNavigation(force = true) }
        waitUntil("the lead is redone to the edited plan", 90_000) {
            traced("Plan edited on the way there") && nav()?.let { it.session?.rerouted == true && it.line != lead } == true
        }
        shot("4-lead-redone")
        // Undo → the led route exactly as before
        scenario.onActivity { vm(it).switchToPlan() }
        waitUntil("Plan again", 10_000) { mode() == AppMode.PLAN }
        undo()
        waitUntil("Undo: two points", 10_000) { waypointCount() == 2 }
        scenario.onActivity { vm(it).continueNavigation(force = true) }
        waitUntil("Undo: the lead is back", 60_000) { nav()?.line?.let { sameLine(it, lead) } == true }
        shot("5-lead-back")
        Thread.sleep(20_000) // standing far away: never a junction cue
        assertTrue("no junction cue while far away: ${cues()}", cues().isEmpty())
    }

    /** 5 · Round trip: delete its start from the map menu → the loop stays closed. */
    @Test fun flow05_roundTripDeleteStartFromMapMenuStaysClosed() {
        planned(THEATRE, STATION, NORTH)
        scenario.onActivity { vm(it).roundTrip() }
        waitUntil("round trip: 4 points", 10_000) { waypointCount() == 4 }
        waitRouted()
        shot("1-round-trip")
        holdOnWaypoint(0)
        shot("2-menu")
        clickText(Txt.s(Res.string.common_delete))
        waitUntil("start deleted", 15_000) { waypointCount() == 3 }
        val pts = read { it.waypoints.toList() }
        assertEquals("the loop stays closed: $pts", pts.first(), pts.last())
        assertTrue(read { it.isRoundTrip() })
        waitRouted()
        shot("3-still-closed")
    }

    /** 6 · During a tour, tap a saved route in My routes → "End the running tour first …", nothing loaded. */
    @Test fun flow06_savedRouteDuringATourIsNotLoaded() {
        planned(THEATRE, STATION)
        val tourId = startTour()
        val before = read { it.waypoints.toList() }
        val lineBefore = nav()!!.line
        val pts = listOf(GpxWaypoint(NORTH.lat, NORTH.lon, "Rundgang A"), GpxWaypoint(NORTH.lat + 0.004, NORTH.lon, "Rundgang B"))
        val name = "Rundgang saved route"
        val savedId = db.insertTour(TourKind.PLANNED, name, Activity.HIKE.name, 2, 450.0, 0.0, 0.0, 90.0,
            listOf(pts.map { TrackPoint(it.lat, it.lon) }), pts)
        try {
            scenario.onActivity { vm(it).switchToPlan() }
            waitUntil("Plan during the tour", 10_000) { mode() == AppMode.PLAN }
            scenario.onActivity { vm(it).routesTab = RoutesTab.PLANNED; vm(it).openRoutes() }
            clickText(name)
            assertNotNull("the box", findFresh(By.text(Txt.s(Res.string.routes_end_tour_first)), 10_000))
            shot("1-end-tour-first")
            assertEquals("the plan stays", before, read { it.waypoints.toList() })
            assertEquals("the followed line stays", lineBefore, nav()?.line)
            assertEquals("the activity stays", Activity.BIKE_TOURING, read { it.activity })
            assertEquals("the same tour records on", tourId, com.onpath.app.recording.RecordingService.state.value?.tourId)
            clickText(Txt.s(Res.string.common_ok))
            assertTrue("the box goes with OK", goneFresh(By.text(Txt.s(Res.string.routes_end_tour_first)), 5_000))
        } finally {
            scenario.onActivity { vm(it).routesOpen = false }
            db.deleteTour(savedId)
        }
    }

    /** 7 · End the tour → Save → it is listed in My routes → Done. */
    @Test fun flow07_endTourSaveListedInDone() {
        planned(THEATRE, STATION)
        val tourId = startTour()
        rideRoute(factor = 5)
        waitUntil("some metres recorded", 60_000) { (com.onpath.app.recording.RecordingService.state.value?.distanceM ?: 0.0) > 100 }
        scenario.onActivity { vm(it).switchToPlan() }
        waitUntil("Plan during the tour", 10_000) { mode() == AppMode.PLAN }
        clickText(Txt.s(Res.string.nav_end_tour))
        assertNotNull("\"End this tour?\"", findFresh(By.text(Txt.s(Res.string.nav_end_tour_title)), 10_000))
        shot("1-end-tour")
        clickText(Txt.s(Res.string.common_save))
        waitUntil("tour saved", 30_000) { RecordingServiceState.idle() && db.getTour(tourId)?.state == "done" }
        Thread.sleep(2_000) // the automatic name
        val name = db.getTour(tourId)!!.name
        scenario.onActivity { vm(it).finishedTour = null; vm(it).routesTab = RoutesTab.DONE; vm(it).openRoutes() }
        assertNotNull("\"$name\" listed in Done", findFresh(By.text(name), 15_000))
        shot("2-listed-in-done")
        scenario.onActivity { vm(it).routesOpen = false }
    }

    /** 8 · Sync: paired with the "browser", a plan saved there arrives in My routes → Planned with the tag "New". */
    @Test fun flow08_browserPlanArrivesTaggedNew() {
        val mailbox = RundgangMailbox()
        PlanSync.mailboxForTests = { mailbox.box(it) }
        java.io.File(ctx.filesDir, com.onpath.app.plansync.SyncFiles.DIR).deleteRecursively()
        val secret = ByteArray(PairCode.SECRET_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        val keys = SyncKeys(secret)
        val title = "Rundgang from the browser ${started % 10_000}"
        try {
            launch { demoAt(THEATRE) }
            // the phone's camera reads the browser's QR code: a pairing link opened in OnPath
            scenario.onActivity { a ->
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PairCode.link(PlanSync.SERVER, secret))).setPackage(a.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            assertNotNull("check digits ${keys.check} shown", findFresh(By.textContains(keys.check.take(3)), 10_000))
            shot("1-pair")
            clickText(Txt.s(Res.string.sync_pair))
            waitUntil("paired", 20_000) { read { it.sync.state.value.paired } }
            shot("2-paired")
            // the browser (the web planner runs the same Kotlin): saves a plan and syncs
            val browser = SyncLedger.empty()
            val web = SyncFormat.newDevice(ByteArray(8) { 7 })
            browser.save(SyncFormat.newId(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }),
                PlanCode.encode(SharedPlan(listOf(THEATRE, NORTH), Activity.HIKE, 2)), title, System.currentTimeMillis(), web)
            val n = java.util.concurrent.atomic.AtomicInteger()
            val out = SyncRound(keys, mailbox.box(keys), { ByteArray(12) { (n.incrementAndGet() and 0x7f).toByte() } }).run(browser)
            assertTrue("browser round: $out", out is SyncRound.Outcome.Done)
            Thread.sleep(11_000) // My routes passes at once when opened, not within 10 s of the pairing pass
            scenario.onActivity { vm(it).routesTab = RoutesTab.PLANNED; vm(it).openRoutes() }
            assertNotNull("\"$title\" arrived", findFresh(By.text(title), 90_000))
            assertNotNull("tag \"New\"", findFresh(By.text(Txt.s(Res.string.routes_tag_new)), 10_000))
            shot("3-arrived-new")
        } finally {
            if (launched) {
                scenario.onActivity { vm(it).routesOpen = false }
                val done = CountDownLatch(1)
                scenario.onActivity { vm(it).sync.unpair { done.countDown() } }
                done.await(10, TimeUnit.SECONDS)
            }
            db.listTours().filter { it.name == title }.forEach { db.deleteTour(it.id) }
            PlanSync.mailboxForTests = null
        }
    }

    /**
     * 9 · Search stays stable (field bug 2026-10-31): a POI name, then a partial town, typed word by word – the exact hit
     * stays on top and the list does not jump to another one while the offline index and the online results come in
     * (fix e6b9c31, unit tests SearchStableListTest).
     */
    @Test fun flow09_searchStaysStable() {
        launch(extraData = { dir -> java.io.File(dir, "pois.geojson").writeText(SEARCH_POIS) }) { demoAt(THEATRE) }
        scenario.onActivity { vm(it).openSearch() }
        val field = device.wait(androidx.test.uiautomator.Until.findObject(By.clazz("android.widget.EditText")), 10_000)
        assertNotNull("search field", field)
        field!!.click()
        device.executeShellCommand("input text sissi")
        Thread.sleep(1_500)
        device.executeShellCommand("input text %smer")
        waitUntil("the exact hit on top", 15_000) { top()?.contains("Sissi") == true }
        val first = results()
        shot("1-results")
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            val now = results()
            assertEquals("the exact hit stays on top (was $first, now $now)", first.first(), now.firstOrNull())
            // rows shown stay in place; online rows only go below
            val shownStill = now.take(first.size)
            assertEquals("the list does not jump (was $first, now $now)", first, shownStill.take(first.size))
            Thread.sleep(250)
        }
        shot("2-after-5s")
    }

    /**
     * 10 · Share a place from Google Maps (field bug 2026-10-31, "Olive Hilden" → Düsseldorf; fix c4f5e99,
     * SharedPlaceLookupTest): the marker is at the shared place, never at a namesake in another town.
     * a) name + address + a `?q=lat,lon` link (no network needed) → the ring and its menu at that coordinate;
     * b) name + address only, a namesake in Bozen in the offline data → never Bozen (Meran found online, or "Place not
     *    clear"). The `maps.app.goo.gl` short-link variant needs the network and Google – left to SharedPlaceLookupTest.
     */
    @Test fun flow10_sharedPlaceIsTheSharedOneNeverANamesake() {
        // no search history (an earlier share of the same place would be found there – right, but not this check)
        ctx.getSharedPreferences("onpath-search", 0).edit().clear().commit()
        launch(extraData = { dir -> java.io.File(dir, "pois.geojson").writeText(SHARE_POIS) }) { demoAt(THEATRE) }
        // b) first, with no history: name + address only
        share("Restaurant Sissi\nGalileistraße 44, 39012 Meran")
        waitUntil("b) the shared place decided (found or \"not clear\")", 45_000) {
            read { vm -> !vm.sharedLookup && (vm.sharedUnsure != null || vm.searchMark != null) }
        }
        val mark = read { it.searchMark }
        shot("1-shared-without-coordinate")
        if (mark != null) {
            val at = GeoPoint(mark.lat, mark.lon)
            assertTrue("b) never the namesake in Bozen (${mark.name} at $at)", Geo.distance(at, BOZEN_NAMESAKE) > 5_000)
            assertTrue("b) in Meran (${mark.name} at $at)", Geo.distance(at, THEATRE) < 5_000)
        }
        scenario.onActivity { vm(it).poiMenu = null }
        Thread.sleep(500)
        // a) with a ?q=lat,lon link: exactly there (a pin 200 m off the restaurant – the link wins; another spot than b,
        // whose menu already opened once: the menu opens once per shared place)
        share("Restaurant Sissi\nGalileistraße 44, 39012 Meran\nhttps://maps.google.com/?q=${PIN.lat},${PIN.lon}")
        waitUntil("a) the ring at the shared coordinate", 30_000) {
            read { vm -> !vm.sharedLookup && vm.searchMark?.let { Geo.distance(GeoPoint(it.lat, it.lon), PIN) < 30 } == true }
        }
        waitUntil("a) its menu opens there", 30_000) { read { it.poiMenu }?.let { Geo.distance(GeoPoint(it.lat, it.lon), PIN) < 30 } == true }
        shot("2-shared-with-coordinate")
    }

    /** What Google Maps' share sheet sends: ACTION_SEND text/plain to OnPath. */
    private fun share(text: String) {
        scenario.onActivity { a ->
            a.startActivity(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                .setPackage(a.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Thread.sleep(1_000)
    }

    // ------------------------------------------------------------------------------------------------ steps

    private fun <T> read(f: (AppViewModel) -> T): T {
        var r: Any? = null
        scenario.onActivity { r = f(vm(it)) }
        @Suppress("UNCHECKED_CAST") return r as T
    }

    private fun mode(): AppMode = read { it.mode }

    /** The same line point by point (within 1 m – the session file keeps coordinates, not every extra). */
    private fun sameLine(a: List<GeoPoint>, b: List<GeoPoint>) = a.size == b.size && a.indices.all { Geo.distance(a[it], b[it]) < 1.0 }
    private fun nav(): NavState? = com.onpath.app.recording.RecordingService.state.value?.nav
    private fun position(): GeoPoint = read { vm -> vm.tracker.location.value!!.let { GeoPoint(it.latitude, it.longitude) } }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun demoAt(p: GeoPoint) = onMain { DemoLocation.jumpTo(p.lat, p.lon, null, "Rundgang place") }

    /** Junction cues of this flow (the service traces every one, vibrated or not). */
    private fun cues() = Problems.events().filter { it.time >= started && it.message.startsWith("Junction cue:") }
    private fun traced(prefix: String) = Problems.events().any { it.time >= started && it.message.startsWith(prefix) }

    private fun requireRealRouting() =
        assertTrue("real routing data needed: push E10_N45.rd5 to /data/local/tmp (tools/ci/release_rundgang.sh does)", realRouting)

    /** A plan of [points] (bike), the demo position at [at]; waits for the routed line. */
    private fun planned(vararg points: GeoPoint, at: GeoPoint = points.first()) {
        launch {
            Prefs(ctx).activity = Activity.BIKE_TOURING
            Prefs(ctx).planWaypoints = points.toList()
            demoAt(at)
        }
        requireRealRouting()
        waitRouted()
    }

    private fun waitRouted() {
        waitUntil("route calculated – ${state()}", 60_000) { read { !it.routing && it.route != null } }
        Thread.sleep(1_000)
    }

    /** Start navigation as the plan's button does (the phone setup check is its own test); returns the tour id. */
    private fun startTour(): Long {
        scenario.onActivity { vm(it).startNavigation(force = true) }
        waitUntil("Navigate (recording)", 20_000) { mode() == AppMode.NAVIGATE }
        waitUntil("the route is followed", 20_000) { nav() != null && read { it.followLine != null } }
        return com.onpath.app.recording.RecordingService.state.value!!.tourId
    }

    /** The demo rides the followed line from its start at the time model's speed × [factor]. */
    private fun rideRoute(factor: Int) {
        val line = nav()!!.line
        onMain { DemoLocation.startRide(DemoPath.timed(line, Activity.BIKE_TOURING)!!, "Rundgang route", factor) }
    }

    /** Rides the first ~250 m of [line], then 220 m away from it to the north, and stands there. */
    private fun leaveTheRoute(line: List<GeoPoint>) {
        waitUntil("on the route", 30_000) { nav()?.shownAlongM != null }
        val cum = Geo.cumulative(line)
        val part = line.filterIndexed { i, _ -> cum[i] <= 250.0 }
        val last = part.last()
        val away = part + GeoPoint(last.lat + 0.002, last.lon)
        onMain { DemoLocation.startRide(DemoPath.constant(away, 5.0)!!, "Rundgang off the route", 5) }
    }

    private fun waitQuestion(): NavPrompt.Question {
        waitUntil("the off-route question", 120_000) { (nav()?.prompt as? NavPrompt.Question)?.switch == null && nav()?.prompt is NavPrompt.Question }
        val q = nav()!!.prompt as NavPrompt.Question
        assertNotNull("\"${NavText.TAKE}\" in the box", findFresh(By.text(NavText.TAKE), 10_000))
        return q
    }

    private fun undo() {
        val o = findFresh(By.desc(Txt.s(Res.string.plan_undo)), 5_000)
        if (o != null) o.click() else scenario.onActivity { vm(it).undoStep() }
        Thread.sleep(500)
    }

    /** The camera shows [a] and [b] in the open middle of the map (following stopped first – map camera rule). */
    private fun showOnMap(a: GeoPoint, b: GeoPoint) {
        scenario.onActivity { vm(it).followMe = false }
        Thread.sleep(300)
        val mid = LatLng((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
        // both points within ±100 dp of the centre: MapLibre's world is 512 dp wide at zoom 0
        val d = Geo.distance(a, b).coerceAtLeast(100.0)
        val cosLat = kotlin.math.cos(Math.toRadians(mid.latitude))
        val zoom = kotlin.math.floor(kotlin.math.log2(40_075_016.0 * cosLat / (512.0 * (d / 200.0)))).coerceIn(10.0, 17.0)
        // the app's own start camera (fly to the position) may still move the map: set it until it stays
        // [mid] at 45 % across, 30 % down: above the plan sheet, which is tall during a tour (Pause / End tour)
        val (w, h) = mapSize()
        repeat(5) {
            var target: LatLng? = null
            onMap { m ->
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(mid, zoom))
                target = m.projection.fromScreenLocation(android.graphics.PointF(w - w * 0.45f, h - h * 0.30f))
                m.moveCamera(CameraUpdateFactory.newLatLng(target!!))
            }
            Thread.sleep(1_500)
            var at: LatLng? = null
            onMap { m -> at = m.cameraPosition.target }
            if (at != null && target != null && Geo.distance(GeoPoint(at!!.latitude, at!!.longitude), GeoPoint(target!!.latitude, target!!.longitude)) < 30) return
        }
        fail("the map camera did not stay at the test place – ${state()}")
    }

    /**
     * A finger held on the map at [p] (projected as MapLibre shows it now). The app may still move the camera (e.g. the
     * Plan view during a tour): [p] must lie in the open map above the sheet, else the map is set on it again.
     */
    private fun holdAt(p: GeoPoint) {
        val (w, h) = mapSize()
        var xy = 0f to 0f
        for (attempt in 1..4) {
            val done = CountDownLatch(1)
            scenario.onActivity { a ->
                val mv = mapView(a)!!
                val loc = IntArray(2).also { mv.getLocationOnScreen(it) }
                mv.getMapAsync { m -> m.projection.toScreenLocation(LatLng(p.lat, p.lon)).let { xy = (loc[0] + it.x) to (loc[1] + it.y) }; done.countDown() }
            }
            done.await(5, TimeUnit.SECONDS)
            if (xy.first in w * 0.1f..w * 0.75f && xy.second in h * 0.15f..h * 0.45f) break
            android.util.Log.i("Rundgang", "$p at screen $xy is not on the open map (attempt $attempt) – setting the map again")
            showOnMap(p, p)
        }
        android.util.Log.i("Rundgang", "hold at $p → screen $xy")
        hold(xy.first, xy.second)
    }

    private fun results(): List<String> = read { vm -> vm.search.ui.results.map { "${it.place.name}@${"%.4f".format(java.util.Locale.ROOT, it.place.lat)}" } }
    private fun top(): String? = results().firstOrNull()

    @After fun endRundgangFlow() {
        try { onMain { DemoLocation.stop("Rundgang flow ended") } } catch (_: Exception) {} // expected: nothing running
        if (launched && com.onpath.app.recording.RecordingService.state.value != null) {
            try { scenario.onActivity { vm(it).endTourAndDelete() } } catch (_: Exception) {} // expected: app already closed
            val end = System.currentTimeMillis() + 10_000
            while (com.onpath.app.recording.RecordingService.state.value != null && System.currentTimeMillis() < end) Thread.sleep(200)
        }
    }

    private object RecordingServiceState { fun idle() = com.onpath.app.recording.RecordingService.state.value == null }

    companion object {
        /** Meran: Theatre square → the station (bike ~1.4 km: 5 junction cues, "pushing · steps"); north of the old town. */
        val THEATRE = GeoPoint(46.6702, 11.1600)
        val STATION = GeoPoint(46.6725, 11.1495)
        val NORTH = GeoPoint(46.6760, 11.1560)
        /** ~1.5 km south of the Theatre (Untermais): far from the route's start. */
        val FAR = GeoPoint(46.6570, 11.1600)

        /** Flow 10: a namesake of the shared restaurant in Bozen (offline data only). */
        /** A dropped pin in Meran (flow 10 a). */
        val PIN = GeoPoint(46.67120, 11.16380)
        val BOZEN_NAMESAKE = GeoPoint(46.49830, 11.35480)
        val SHARE_POIS = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[11.35480,46.49830]},"properties":{"kind":"restaurant","name":"Restaurant Sissi","id":"rundgang-bz"}}
        ]}"""

        /** Flow 9's offline POIs: the exact hit and two decoys with the same word. */
        val SEARCH_POIS = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[11.1655,46.6700]},"properties":{"kind":"restaurant","name":"Restaurant Sissi","id":"rundgang-1"}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[11.1570,46.6690]},"properties":{"kind":"cafe","name":"Café Sissi","id":"rundgang-2"}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[11.1350,46.6780]},"properties":{"kind":"viewpoint","name":"Sissi-Blick","id":"rundgang-3"}}
        ]}"""
    }
}
