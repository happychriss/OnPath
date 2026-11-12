package com.onpath.app.navigation

import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.navigation.Trace.walk
import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `nav-session.json` has two writers in one process: the app (`AppViewModel.navStore` – start / change / end of what
 * is followed) and the service (`RecordingService.sessionStore` – re-routes, reached, visited stops). Code audit
 * 2026-11-12 suspected torn or lost sessions. Safety net: many writers / a reader during a write never see half a
 * file; the real call orders (edit during a tour, guidance ended, service without a file, restart) keep the newer
 * session; a missing / empty / torn / garbage file loads as "no session", never an exception.
 */
class NavSessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val file get() = File(tmp.root, NavSession.FILE)

    /** A session whose whole content follows from (w, r): name, token and the line's length must agree when loaded. */
    private fun session(w: Int, r: Int, stops: Boolean = true): NavSession {
        val lenM = 1000.0 + 10.0 * (w * 50 + r)
        val l = line(0.0 to 0.0, lenM to 0.0)
        return NavSession.start(NavKind.PLAN, "w${w}r$r", Activity.HIKE, 3, l,
            if (stops) listOf(pt(0.0, 0.0), pt(lenM, 0.0)) else emptyList())
            .copy(token = w * 100_000L + r, version = r, visited = setOf(0), reached = true)
    }

    private fun expectedSize(w: Int, r: Int) = (1000 + 10 * (w * 50 + r)) / 10 + 1

    /** The loaded session is one complete written session: never fields of two writers, never a shortened line. */
    private fun assertWhole(s: NavSession?) {
        assertNotNull("a session loads (the file is never missing or torn)", s)
        val w = (s!!.token / 100_000L).toInt(); val r = (s.token % 100_000L).toInt()
        assertEquals("w${w}r$r", s.name)
        assertEquals(r, s.version)
        assertEquals("line length of writer $w round $r", expectedSize(w, r), s.original.size)
        assertEquals(2, s.stops.size)
        assertEquals(s.original.last().lon, s.stops.last().point.lon, 1e-9)
        assertEquals(setOf(0), s.visited)
    }

    private fun run(threads: Int, body: (Int) -> Unit): List<Throwable> {
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) { t -> pool.execute { start.await(); try { body(t) } catch (e: Throwable) { errors += e } } }
        start.countDown()
        pool.shutdown()
        assertTrue("threads finished", pool.awaitTermination(120, TimeUnit.SECONDS))
        return errors
    }

    // --- (a) two store instances, two threads, one file ---

    @Test fun twoStoresOnOneFileFromTwoThreadsAlwaysLoadOneWholeSession() {
        val stores = listOf(NavSessionStore(file), NavSessionStore(file)) // app and service each have their own
        val rounds = 150
        val errors = run(3) { t ->
            if (t < 2) repeat(rounds) { r ->
                stores[t].save(session(t, r))
                assertWhole(stores[t].load()) // own or the other's – but whole
            } else {
                while (!file.isFile) Thread.yield()
                repeat(600) { assertWhole(stores[0].load()) }
            }
        }
        assertEquals("no thread failed: ${errors.firstOrNull()}", emptyList<Throwable>(), errors)
        assertWhole(NavSessionStore(file).load())
        assertEquals("no temp file left behind", listOf(NavSession.FILE), tmp.root.list()!!.toList())
    }

    // --- (b) a load during a save ---

    @Test fun aLoadDuringASaveSeesTheOldOrTheNewSessionNeverHalf() {
        val a = session(1, 0).copy(original = line(0.0 to 0.0, 30_000.0 to 0.0)) // ~3000 points: a long write
        val b = session(2, 0).copy(original = line(0.0 to 0.0, 36_000.0 to 0.0))
        val writer = NavSessionStore(file); val reader = NavSessionStore(file)
        writer.save(a)
        val seen = Collections.synchronizedSet(mutableSetOf<Long>())
        val errors = run(2) { t ->
            if (t == 0) repeat(200) { i -> writer.save(if (i % 2 == 0) b else a) }
            else repeat(400) {
                val s = reader.load()
                assertNotNull("the file is there the whole time", s)
                val want = if (s!!.token == a.token) a else b
                assertEquals("token ${s.token}", want.token, s.token)
                assertEquals("line of ${s.name}", want.original.size, s.original.size)
                assertEquals(want.original.last().lon, s.original.last().lon, 1e-9)
                seen += s.token
            }
        }
        assertEquals("no thread failed: ${errors.firstOrNull()}", emptyList<Throwable>(), errors)
        assertTrue("the reader saw a complete session", seen.isNotEmpty())
    }

    // --- (c) the real call order of app and service (RecordingService.handle, lines "the app chose a new line
    // meanwhile": the service writes a re-route only over its own session) ---

    /** What the service does with a session whose line / fields changed: true = written. */
    private fun serviceWrites(store: NavSessionStore, s: NavSession): Boolean {
        if (!NavSession.mayOverwrite(store.tokenOnDisk(s.token), s.token)) return false
        store.save(s)
        return true
    }

    /** The service's navigator takes a found way back ("Take new route"): the session line changes, the token stays. */
    private fun rerouted(s: NavSession): NavSession {
        val nav = Navigator(s)
        val job = walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0).mapNotNull { nav.onFix(it).job }.last()
        val leg = line(1150.0 to 0.0, 1000.0 to 0.0, 1000.0 to 200.0)
        val resume = 1200.0
        val l = RerouteTargets.splice(leg, NavLine(s.original), resume)
        nav.onResult(job.id, Rerouter.Found(l, com.onpath.app.data.Geo.length(leg), resume, 120.0, 110.0, 5.0, null, emptySet(), 2, 15))
        nav.take((nav.prompt as NavPrompt.Question).id)
        assertTrue("the navigator took the new line", nav.session.rerouted)
        assertEquals("a re-route keeps the token", s.token, nav.session.token)
        return nav.session
    }

    private fun tour(w: Int, r: Int) = NavSession.start(NavKind.PLAN, "w${w}r$r", Activity.HIKE, 3,
        line(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 1000.0), listOf(pt(0.0, 0.0), pt(1000.0, 0.0), pt(1000.0, 1000.0)))
        .copy(token = w * 100_000L + r, reached = true)

    @Test fun aReRouteWritesOverItsOwnSessionAndSurvivesARestart() {
        val app = NavSessionStore(file); val service = NavSessionStore(file)
        app.save(tour(1, 1))                       // the app starts navigation
        val running = service.load()!!             // the service loads it (start / restart)
        val re = rerouted(running)
        assertTrue(serviceWrites(service, re))     // its re-route is written
        val after = NavSessionStore(file).load()!! // process death + restart: the re-routed line and the token are kept
        assertTrue(after.rerouted); assertEquals(running.token, after.token)
        assertEquals(re.current!!.size, after.current!!.size)
    }

    @Test fun aReRouteOfTheOldLineNeverOverwritesThePlanEditedMeanwhile() {
        val app = NavSessionStore(file); val service = NavSessionStore(file)
        app.save(tour(1, 1))
        val running = service.load()!!
        app.save(tour(1, 2))                       // the plan is edited during the tour: a new session, new token
        assertFalse("the result belongs to the old session", serviceWrites(service, rerouted(running)))
        val onDisk = NavSessionStore(file).load()!!
        assertEquals(tour(1, 2).token, onDisk.token); assertFalse(onDisk.rerouted)
    }

    /**
     * Guidance ended in the app (plan emptied during the tour, `clearFollow`): the file is deleted, FOLLOW_CHANGED
     * reaches the service a moment later. A re-route result already queued on the main thread lands in between – its
     * session must not come back ("Guide me" takes a found way at once; with it the service re-saved the followed
     * line too, and FOLLOW_CHANGED then loaded the route the user had removed).
     */
    @Test fun aReRouteAfterTheAppEndedGuidanceDoesNotBringTheSessionBack() {
        val app = NavSessionStore(file); val service = NavSessionStore(file)
        app.save(tour(1, 1))
        val running = service.load()!!
        app.save(null)                             // clearFollow: no session file any more
        assertFalse("the app ended the guidance", serviceWrites(service, rerouted(running)))
        assertNull(NavSessionStore(file).load()); assertFalse(file.exists())
    }

    @Test fun theServicesOwnSessionWithoutAFileStillWrites() {
        // no nav-session.json (an older app, or the app's session start failed): the service follows the plain line
        // with token 0 and must be able to save its re-routes, else every result were dropped
        val service = NavSessionStore(file)
        val own = tour(0, 0).copy(token = 0L)
        assertTrue(serviceWrites(service, rerouted(own)))
        assertTrue(NavSessionStore(file).load()!!.rerouted)
    }

    @Test fun anUnreadableFileIsReplacedByTheServicesCompleteSession() {
        val service = NavSessionStore(file)
        val running = tour(1, 1)
        file.writeText("{\"v\":1,\"orig") // torn by the disk, not by a writer (writes are atomic)
        assertNull(service.load())
        assertTrue("an unreadable file is nobody's newer session", serviceWrites(service, rerouted(running)))
        assertEquals(running.token, NavSessionStore(file).load()!!.token)
    }

    @Test fun theProposalFileIsReadOnceThenGone() {
        // ActivityCheck.PROPOSAL_FILE: the app writes it, the service loads and deletes it (ACTION_PROPOSE_SWITCH)
        val f = File(tmp.root, ActivityCheck.PROPOSAL_FILE)
        NavSessionStore(f).save(tour(3, 3).copy(token = 0L))
        val proposed = NavSessionStore(f).load()
        f.delete()
        assertNotNull(proposed); assertNull(NavSessionStore(f).load())
    }

    // --- (d) broken files ---

    @Test fun missingEmptyTornAndGarbageFilesLoadAsNoSessionAndNeverThrow() {
        val store = NavSessionStore(file)
        assertNull("missing", store.load())
        val whole = NavSessionStore.toJson(session(1, 1)).toString()
        val broken = listOf(
            "" to "empty",
            whole.substring(0, whole.length / 2) to "truncated",
            whole.substring(0, 1) to "one brace",
            "not json at all" to "garbage",
            "\u0000\u0001\u0002" to "binary",
            "[]" to "array",
            "{}" to "empty object",
            "{\"v\":1}" to "no line",
            "{\"v\":1,\"original\":[1.0]}" to "no usable line",
            "{\"v\":1,\"original\":\"x\"}" to "wrong type",
            "{\"v\":1,\"original\":[1.0,2.0,null,3.0,\"x\",null]}" to "a point of the wrong type",
        )
        for ((text, what) in broken) {
            file.writeText(text)
            assertNull(what, store.load())
        }
        // odd optional parts are tolerated (older files): stops of the wrong type = no stops, the line stays
        file.writeText(whole.replace("\"stops\"", "\"stops\":5,\"x\""))
        assertEquals(0, store.load()!!.stops.size)
        // a folder in the file's place
        file.delete(); file.mkdirs()
        assertNull("a folder", store.load())
        file.delete()
        // and a good file after all that loads again
        store.save(session(2, 2))
        assertWhole(store.load())
    }

    @Test fun aSaveThatCannotWriteFailsQuietly() {
        val store = NavSessionStore(file)
        store.save(session(1, 1))
        // the folder goes away under the store and cannot come back (a plain file in its place): the save fails
        // quietly (reported to Problems), the next load finds no session – never an exception
        assertTrue(tmp.root.deleteRecursively()); tmp.root.writeText("in the way")
        store.save(session(1, 2))
        assertNull(store.load())
        tmp.root.delete()
    }
}
