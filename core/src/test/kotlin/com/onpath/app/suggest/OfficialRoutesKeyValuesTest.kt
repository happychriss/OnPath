package com.onpath.app.suggest

import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Prefer official routes" (Settings → Map): which BRouter profile variables each strength sets. Read from
 * [RouterProfile.keyValues] – the verbatim copy of BRouterEngine's keyValues (RouterProfileTest keeps the two the same).
 */
class OfficialRoutesKeyValuesTest {
    private val routeKeys = listOf("hiking_routes_preference", "cycleroutes_pref", "stick_to_cycleroutes")

    @Test fun offSetsNothingSoTheProfilesOwnValuesApply() {
        for (a in Activity.entries) {
            val kv = RouterProfile.keyValues(a, 3, official = 0)
            for (k in routeKeys) assertNull("$a $k", kv[k])
            assertEquals("the default is off", kv, RouterProfile.keyValues(a, 3))
        }
    }

    @Test fun normalAndStrongWeighTheMarkedNetworks() {
        assertEquals("0.8", RouterProfile.keyValues(Activity.HIKE, 3, official = 1)["hiking_routes_preference"])
        assertEquals("2.0", RouterProfile.keyValues(Activity.HIKE, 3, official = 2)["hiking_routes_preference"])
        for (a in listOf(Activity.MTB, Activity.EMTB)) {
            assertEquals("$a", "0.4", RouterProfile.keyValues(a, 3, official = 1)["cycleroutes_pref"])
            assertEquals("$a", "1.0", RouterProfile.keyValues(a, 3, official = 2)["cycleroutes_pref"])
        }
        for (a in listOf(Activity.BIKE_TOURING, Activity.EBIKE, Activity.BIKE_TREKKING)) {
            assertEquals("$a", "1", RouterProfile.keyValues(a, 3, official = 1)["stick_to_cycleroutes"])
            assertEquals("$a", "1", RouterProfile.keyValues(a, 3, official = 2)["stick_to_cycleroutes"])
        }
        // the hiking SAC limits and the e-bike values stay as they are
        assertEquals("3", RouterProfile.keyValues(Activity.HIKE, 3, official = 2)["SAC_scale_limit"])
        assertEquals("180", RouterProfile.keyValues(Activity.EBIKE, 3, official = 2)["bikerPower"])
    }

    @Test fun anUnknownStrengthIsClamped() {
        assertNull(RouterProfile.keyValues(Activity.HIKE, 3, official = -1)["hiking_routes_preference"])
        assertEquals("2.0", RouterProfile.keyValues(Activity.HIKE, 3, official = 7)["hiking_routes_preference"])
    }
}
