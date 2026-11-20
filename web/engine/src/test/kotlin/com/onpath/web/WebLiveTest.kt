package com.onpath.web

import com.onpath.app.live.LiveCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The viewer page's calls (WebLive, docs/design/live-tracking.md §14.5) on the JVM. Guards: the page derives the same
 * box and read token as the phone (the frozen values of LiveFormatTest – else it asks a box that does not exist); what
 * the phone seals the page opens, and nothing else does (another name, another link, a changed byte → "null", never a
 * half-read tour); the status line and the next-ask time are the shared rules, passed through unchanged.
 */
class WebLiveTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val secret = ByteArray(32) { (it * 7 + 1).toByte() }
    private val link = LiveCode.link(secret)
    private val nonce = b64.encodeToString(ByteArray(12) { 9 })

    @Test fun theKeysAreThePhones() {
        // test vector from the dummy secret i*7+1 (or a made-up test token) – not a credential
        assertEquals("{\"box\":\"0dPnimRkezLc3fCguuX6rg\",\"token\":\"aFVOTMzUKm5qEBrhELBD-BkTFhqMML-Q55w9JiKjmjo\"}", WebLive.keys(link)) // ggignore
        assertEquals(WebLive.keys(link), WebLive.keys("#" + link.substringAfter('#'))) // what the page passes: location.hash
        assertEquals("null", WebLive.keys("https://plan.onpath.earth/live/"))
        assertEquals("null", WebLive.keys("#t=abc"))
        assertEquals(link, WebLive.link(b64.encodeToString(secret), LiveCode.PAGE))
    }

    @Test fun whatThePhoneSealsThePageOpens() {
        val head = WebLive.sealHead(link, "state=paused&lat=46.6935&lon=11.2412&ele=1890&time=1000&distanceM=5400&ascentM=310&movingS=4500" +
            "&toGoM=6200&arrivalMs=9000&batteryPct=43&activity=HIKE&name=Christian&nextS=600&chunks=2&stillS=420", nonce)
        val h = WebLive.open(link, "h", head)
        for (part in listOf("\"kind\":\"head\"", "\"state\":\"paused\"", "\"lat\":46.6935", "\"ele\":1890", "\"distanceM\":5400", "\"toGoM\":6200",
            "\"batteryPct\":43", "\"name\":\"Christian\"", "\"nextS\":600", "\"chunks\":2", "\"stillS\":420", "\"movingText\":\"1:15 h\""))
            assertTrue("$part in $h", h.contains(part))
        val route = WebLive.sealRoute(link, "46.1,11.1;46.2,11.2", nonce)
        assertEquals("{\"kind\":\"route\",\"line\":[[46.1,11.1],[46.2,11.2]]}", WebLive.open(link, "r", route))
        val chunk = WebLive.sealChunk(link, 3, "0,46.1,11.1,500,1000;1,46.2,11.2,510,2000", nonce)
        assertEquals("{\"kind\":\"chunk\",\"n\":3,\"points\":[[0,46.1,11.1,500,1000],[1,46.2,11.2,510,2000]]}", WebLive.open(link, "t0003", chunk))
        // nothing else opens it
        assertEquals("null", WebLive.open(link, "t0004", chunk))
        assertEquals("null", WebLive.open(link, "r", head))
        assertEquals("null", WebLive.open(LiveCode.link(ByteArray(32) { 5 }), "h", head))
        assertEquals("null", WebLive.open(link, "h", head.dropLast(2) + "AA"))
    }

    @Test fun theStatusLineAndThePacingAreTheSharedRules() {
        val moving = WebLive.sealHead(link, "state=recording&lat=46.1&lon=11.1&time=5000&nextS=120", nonce)
        fun key(json: String) = Regex("\"key\":\"([a-z_]+)\"").find(json)!!.groupValues[1]
        assertEquals("moving", key(WebLive.status(link, moving, false, false, 125_000.0, -1.0)))
        assertTrue(WebLive.status(link, moving, false, false, 125_000.0, -1.0).contains("\"minutes\":2"))
        assertEquals("no_contact", key(WebLive.status(link, moving, false, false, 25 * 60_000.0, -1.0)))
        assertEquals("ended", key(WebLive.status(link, moving, false, false, 0.0, 7000.0)))
        assertEquals("stopped", key(WebLive.status(link, moving, true, false, 0.0, -1.0)))
        assertEquals("no_data", key(WebLive.status(link, "", false, false, 0.0, -1.0)))
        // a link without a key, or a head that does not open with it: broken – never "no data"
        assertEquals("broken", key(WebLive.status("#t=abc", "", false, false, 0.0, -1.0)))
        assertEquals("broken", key(WebLive.status(link, moving.dropLast(2) + "AA", false, false, 0.0, -1.0)))
        // the pacing: shortly after the phone's next planned upload; never again when it is over
        assertEquals(120_000.0, WebLive.next(10_000.0, 120, 0, false, -1.0), 0.0)
        assertEquals(60_000.0, WebLive.next(0.0, -1, 1, false, -1.0), 0.0)
        assertEquals(-1.0, WebLive.next(0.0, 120, 0, true, -1.0), 0.0)
    }
}
