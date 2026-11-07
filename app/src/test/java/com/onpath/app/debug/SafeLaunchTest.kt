package com.onpath.app.debug

import com.onpath.app.routing.Activity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Background work reports its failures instead of crashing or vanishing (owner 2026-11-07, audit package 1). */
class SafeLaunchTest {
    @Before fun setUp() { Problems.clear(); EnumWarnings.reset() }

    private fun errors(area: String) = Problems.snapshot().filter { it.area == area && it.level == Problems.Level.ERROR }

    @Test fun launchSafeReportsAFailureAndTheScopeGoesOn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launchSafe("test", "Loading the thing") { error("boom") }.join()
        assertEquals(1, errors("test").size)
        assertEquals("Loading the thing failed", errors("test").single().message)
        // the scope is still alive: a second launch runs
        val ran = CompletableDeferred<Boolean>()
        scope.launchSafe("test", "Second") { ran.complete(true) }
        assertTrue(withTimeout(2_000) { ran.await() })
        scope.cancel()
    }

    @Test fun cancellationIsNeverReported() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launchSafe("test", "Waiting") { delay(10_000) }
        delay(50)
        job.cancel(CancellationException("not wanted any more"))
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(errors("test").isEmpty())
        scope.cancel()
    }

    @Test fun aReportedScopeReportsUncaughtErrorsOfPlainLaunches() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).reported("helper", "Helper work failed")
        scope.launch { error("boom") }.join()
        assertEquals("Helper work failed", errors("helper").single().message)
        scope.cancel()
    }

    @Test fun enumOrKnownValueNullAndUnknown() {
        assertEquals(Activity.BIKE_TOURING, enumOr("BIKE_TOURING", Activity.HIKE, "recording"))
        assertEquals(Activity.HIKE, enumOr(null, Activity.HIKE, "recording")) // nothing stored: expected, no warning
        assertTrue(Problems.snapshot().none { it.area == "recording" })
        assertEquals(Activity.HIKE, enumOr("SKATEBOARD", Activity.HIKE, "recording"))
        val w = Problems.snapshot().single { it.area == "recording" }
        assertEquals(Problems.Level.WARN, w.level)
        assertTrue(w.message.contains("SKATEBOARD"))
    }

    @Test fun anUnknownValueIsWarnedOnlyOnce() {
        repeat(5) { assertNull(enumOrNull<Activity>("ROLLER", "settings")) }
        assertEquals(1, Problems.snapshot().single { it.area == "settings" }.count)
    }
}
