package com.onpath.web

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Points list's drag on the page (WebRules.pointsDrag = the app's PlanPoints.dropIndex / shift, sortable.ts). */
class WebPointsDragTest {
    @Test fun dropRowAndTheRowsThatMoveAside() {
        // row 1 dragged 2 rows down (rows 40 px): lands on 3, rows 2 and 3 move up
        assertEquals("{\"to\":3,\"shift\":[0,0,-1,-1,0]}", WebRules.pointsDrag(1, 80.0, 40.0, 5))
        // up past the top: row 0, rows 0..2 move down
        assertEquals("{\"to\":0,\"shift\":[1,1,1,0]}", WebRules.pointsDrag(3, -500.0, 40.0, 4))
        // under half a row: stays
        assertEquals("{\"to\":2,\"shift\":[0,0,0]}", WebRules.pointsDrag(2, 19.0, 40.0, 3))
        // not measured: stays
        assertEquals("{\"to\":1,\"shift\":[0,0]}", WebRules.pointsDrag(1, 300.0, 0.0, 2))
    }
}
