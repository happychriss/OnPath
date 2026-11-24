package com.onpath.app.ui

import com.onpath.app.testkit.sourceFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text pins for [LiveShares] (needs Android's view model, so no behaviour test): one slice per view model, also while sharing is off. */
class LiveShareWiringTest {
    private val src = sourceFile("ui/LiveShareWiring.kt").readText()
    private val attach = src.substringAfter("fun attach(").substringBefore("private const val TICK_MS")

    @Test fun attachGuardsOnTheHolderNotOnOf() {
        assertTrue(attach.contains("if (vm.getCloseable<Holder>(KEY) != null) return"))
        assertFalse("of() is null while sharing is off: every onCreate would add a slice", attach.contains("if (of(vm) != null) return"))
    }

    @Test fun theHolderStopsTheCollectorsItStarted() {
        assertTrue(src.contains("override fun close() { jobs.forEach { it.cancel() } }"))
        assertTrue(attach.contains("holder.jobs = listOf(follow, rec)"))
    }
}
