package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CompassSpecTest {
    @Test fun `walking direction up passes no bearing - the arrow does not turn, the controls need not recompose`() {
        assertEquals(0f, CompassSpec.bearingShown(headingUp = true, bearing = 137.4f))
        assertEquals(0f, CompassSpec.bearingShown(headingUp = true, bearing = 0.2f))
    }

    @Test fun `north up or turned by hand passes the map's bearing to the needle`() {
        assertEquals(35f, CompassSpec.bearingShown(headingUp = false, bearing = 35f))
        assertEquals(0f, CompassSpec.bearingShown(headingUp = false, bearing = 0f))
    }
}
