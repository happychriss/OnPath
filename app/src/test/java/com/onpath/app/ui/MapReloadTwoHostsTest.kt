package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * New map areas must show without an app restart, also when two activities – each with its own ViewModel and MapHost –
 * live at the same time for a moment (event log: "Plan restored at app start" twice within one second, two style
 * loads). With process-wide reload / controller refs and "map dir on screen":
 *  - the short-lived host, disposed AFTER the other one had set the refs, clears them → every later reload is a silent
 *    no-op;
 *  - the "is the map behind the data?" check reads the global dir, which the other host has already moved on.
 * The rules below are per host; this test replays that sequence.
 */
class MapReloadTwoHostsTest {
    private val g48 = File("cells/g000048")
    private val g49 = File("cells/g000049")
    private val g50 = File("cells/g000050")

    private class Host(val name: String) { val view = MapHostView(); var loads = 0 }

    private fun ready(h: Host, refs: OwnedRef<Host>, dir: File) {
        h.view.ready { h.loads++ }
        h.view.loading(dir)
        refs.set(h, h)
    }

    @Test fun shortLivedSecondHostNeverSwitchesOffTheOther() {
        val refs = OwnedRef<Host>()
        val a = Host("A"); val b = Host("B")
        ready(a, refs, g48) // activity 1: style load #1
        ready(b, refs, g48) // activity 2: style load #2 – the refs now point at B
        refs.clear(a) // activity 1 goes away later: it must not clear B's refs
        assertSame(b, refs.get())
        refs.clear(b)
        assertNull(refs.get())
    }

    @Test fun eachHostKnowsWhatItShows() {
        val a = Host("A"); val b = Host("B")
        val refs = OwnedRef<Host>()
        ready(a, refs, g48); ready(b, refs, g48)
        // new generation: host A reloads first (its ViewModel's gate was quicker)
        assertTrue(a.view.behind(g49))
        a.view.reload(); a.view.loading(g49)
        // host B still shows g48 – it must reload too (a global "dir on screen" said g49 and stopped it)
        assertTrue(b.view.behind(g49))
        assertTrue(b.view.reload())
        assertEquals(1, b.loads)
        b.view.loading(g49)
        assertFalse(b.view.behind(g49))
    }

    @Test fun hostWithoutMapYetIsNotBehindAndCannotReload() {
        val v = MapHostView()
        assertFalse(v.behind(g49)) // its own first load takes the newest data
        assertFalse(v.reload())
        v.ready { }
        v.loading(g48)
        assertTrue(v.behind(g50))
        v.disposed()
        assertFalse(v.reload()) // a disposed map is never asked again
        assertFalse(v.behind(g50))
    }

    /** The ViewModel asks for a reload by what IT last asked for – not by what another activity's map loaded. */
    @Test fun reloadAskedPerViewModel() {
        assertTrue(StyleReloadRule.ask(current = g49, lastAsked = g48))
        assertFalse(StyleReloadRule.ask(current = g49, lastAsked = g49))
        // another host already loaded g49 (global dir = g49): this ViewModel still asks, its own map shows g48
        assertTrue(StyleReloadRule.ask(current = g49, lastAsked = g48))
    }
}
