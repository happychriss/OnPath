package com.onpath.app.routing

/**
 * JVM harness only (run.sh RAW_TAP=1): run.sh's copy of BRouterEngine calls [tap] with every track it turns into a
 * result, so a test sees BRouter's full way tags of the app's real route (the app keeps only [WayAttrs]). Routing is
 * unchanged – this only reads the found track.
 */
object RawTap {
    /** Node lon / lat in micro-degrees (ilon − 180e6, ilat − 90e6) and BRouter's aggregated messages of one track. */
    @Volatile var sink: ((IntArray, IntArray, List<String?>) -> Unit)? = null

    fun tap(track: btools.router.OsmTrack) {
        val s = sink ?: return
        val els = track.nodes
        s(IntArray(els.size) { els[it].getILon() - 180_000_000 }, IntArray(els.size) { els[it].getILat() - 90_000_000 },
            track.aggregateMessages())
    }
}
