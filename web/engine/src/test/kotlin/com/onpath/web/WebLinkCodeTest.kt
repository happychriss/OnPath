package com.onpath.web

import com.onpath.app.routing.PlanCode
import org.junit.Assert.assertEquals
import org.junit.Test

/** [Engine.linkCode] finds the code by [PlanCode.parse]'s rule, without its Regex (test/longlink-engine.mjs: why). */
class WebLinkCodeTest {
    @Test fun aLinkGivesItsCodeByTheRuleOfPlanCode() {
        assertEquals("AbC-_1", Engine.linkCode(" https://plan.onpath.earth/#p=AbC-_1 "))
        assertEquals("AbC", Engine.linkCode("https://x/?a=1&p=AbC&b=2"))
        assertEquals("AbC", Engine.linkCode("p=AbC"))
        assertEquals("AbC", Engine.linkCode("https://x/#map=1&top=2&p=AbC")) // "p=" inside "top=" is no marker
        assertEquals("AbC", Engine.linkCode("AbC")) // a bare code stays
        assertEquals("#p=", Engine.linkCode("#p=")) // no code: PlanCode.parse says no
        val long = "x".repeat(16_001)
        assertEquals("#p=$long", Engine.linkCode("#p=$long")) // over the limit: PlanCode.parse refuses it
        assertEquals(null, PlanCode.parse(Engine.linkCode("#p=$long")))
    }
}
