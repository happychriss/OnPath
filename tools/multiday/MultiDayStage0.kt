// Multi-day tours stage 0 (docs/design/multi-day-tours.md §6): a long GPX track (München–Venedig, ~540 km) through the
// shared TrackToPlan as the planner's "Plan a route from it" does, the plan code size of the result, and the first route
// of the whole plan with the shared Planner + LegCache (time, peak heap), then the same points again (cache).
//   tools/multiday/run.sh <track.gpx> <segmentsDir> [workDir]   (sets KTTEST / EXTRA_KT for tools/routing/jvm-test/run.sh)
// Env: MD_RUNS="HIKE:3,HIKE:4,BIKE_TOURING:2" (activity:T level, default HIKE:3); MD_ROUTE=1 routes the fitted plan;
//      TTP_MAX_CALLS=<n> (run.sh patches TrackToPlan.MAX_CALLS, default 60 = today); JAVA_XMX as run.sh.
// The GPX stays outside the repo (tools/multiday/wmt_to_gpx.py builds it from the OSM relation).
import com.onpath.app.routing.*
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun heapPools() = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
fun resetPeak() { System.gc(); heapPools().forEach { it.resetPeakUsage() } }
/** Peak heap used since [resetPeak] in MB (sum of the pools' peaks – an upper bound). */
fun peakMb() = heapPools().sumOf { it.peakUsage.used } / 1_048_576

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val seg = File("/home/user/build/routing-test/segments")
    val xml = File(System.getenv("TRACK_GPX") ?: error("TRACK_GPX=<file.gpx> needed")).readText()
    val track = Regex("<trkpt lat=\"([-\\d.]+)\" lon=\"([-\\d.]+)\"").findAll(xml)
        .map { GeoPoint(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }.toList()
    println("track: %d points, %.1f km; TrackToPlan.MAX_CALLS = %d; max heap %d MB".format(
        track.size, com.onpath.app.data.Geo.length(track) / 1000, TrackToPlan.MAX_CALLS, Runtime.getRuntime().maxMemory() / 1_048_576))
    if (System.getenv("MD_SYNC") == "1") {
        // the track as a sync item (an imported track, sync/TrackCode.fit: thinned to SyncFormat.MAX_TRACK bytes);
        // tools/multiday/run.sh compiles sync/TrackCode.kt with a stand-in for the rest of sync/. The OSM line has no elevation:
        // a second run with a made-up elevation per point (a GPX from a website usually has one) shows its cost.
        for (withEle in listOf(false, true)) {
            val pts = track.mapIndexed { i, p -> com.onpath.app.sync.TrackPt(p.lat, p.lon, if (withEle) 1000 + 800 * Math.sin(i / 400.0) + (i % 7) else null) }
            val t0 = System.currentTimeMillis()
            val text = com.onpath.app.sync.TrackCode.fit(com.onpath.app.sync.SyncTrack(listOf(pts), Activity.HIKE, 3, emptyList()))!!
            val back = com.onpath.app.sync.TrackCode.decode(java.util.Base64.getUrlDecoder().decode(text))!!.segments.flatten().map { GeoPoint(it.lat, it.lon) }
            val off = track.filterIndexed { i, _ -> i % 5 == 0 }.map { com.onpath.app.data.Geo.distanceToLine(it.lat, it.lon, back) }.sorted()
            println("== sync item%s: %d → %d points, %d bytes (limit %d), %.1f s; the thinned line off the track: median %.0f m, 95 %% %.0f m, max %.0f m".format(
                if (withEle) " (with elevation)" else "", track.size, back.size, java.util.Base64.getUrlDecoder().decode(text).size, com.onpath.app.sync.SyncFormat.MAX_TRACK,
                (System.currentTimeMillis() - t0) / 1000.0, off[off.size / 2], off[off.size * 95 / 100], off.last()))
        }
    }
    for (run in (System.getenv("MD_RUNS") ?: "HIKE:3").split(",").filter { it.isNotBlank() }) {
        val (an, lv) = run.split(":"); val act = Activity.valueOf(an); val sac = lv.toInt()
        val eng = BRouterEngine(FakeCtx(files), seg)
        resetPeak()
        var t0 = System.currentTimeMillis()
        val r = TrackToPlan.fit(track, eng, act, sac)
        val fitMs = System.currentTimeMillis() - t0
        val code = PlanCode.encode(SharedPlan(r.points, act, sac, title = "München – Venedig"))
        val gaps = r.points.zipWithNext().map { (a, b) -> com.onpath.app.data.Geo.distance(a, b) / 1000 }
        println("== %s T%d fit: %d route points, follows %.1f %%, %d calls, %d failed legs, %.1f s, peak heap %d MB".format(
            an, sac, r.points.size, r.follows * 100, r.calls, r.failedLegs, fitMs / 1000.0, peakMb()))
        println("   plan code: %d characters (limit 16 000), %d points (limit 500), %.1f chars / point; straight gap between points: median %.2f km, max %.1f km".format(
            code.length, r.points.size, code.length.toDouble() / r.points.size, gaps.sorted()[gaps.size / 2], gaps.max()))
        System.getenv("MD_CODE_OUT")?.let { File("$it-$an-T$sac.txt").writeText(code) } // for the browser run (#p=<code>)
        if (System.getenv("MD_ROUTE") != "1") continue
        // first route of the whole plan: a fresh engine, the planner's LegCache (default size) – like a page that opens the plan
        for ((label, cache) in listOf("LegCache() default ${LegCache.CAPACITY}" to LegCache(), "LegCache(${r.points.size + 1})" to LegCache(r.points.size + 1))) {
            val e2 = BRouterEngine(FakeCtx(files), seg)
            resetPeak()
            t0 = System.currentTimeMillis()
            val p = Planner(e2, legs = cache)
            val pr = p.plan(r.points, act, sac)
            val firstMs = System.currentTimeMillis() - t0
            val failed = p.tries.count { it.outcome != "ok" }
            println("   first route [%s]: %.1f s, %.1f km, ↑%.0f m, %d requests (%d not ok), error=%s, peak heap %d MB, longest request %.1f s".format(
                label, firstMs / 1000.0, pr.distanceM / 1000, pr.ascentM, p.tries.size, failed, pr.error?.take(80), peakMb(),
                (p.tries.maxOfOrNull { it.ms } ?: 0) / 1000.0))
            if (label.startsWith("LegCache()")) legReport(track, r.points, pr)
            t0 = System.currentTimeMillis()
            val p2 = Planner(e2, legs = cache)
            p2.plan(r.points, act, sac)
            println("   same points again: %.1f s, %d requests, %d legs from the cache".format(
                (System.currentTimeMillis() - t0) / 1000.0, p2.tries.size, p2.cachedLegs))
        }
    }
}

/** Where the routed plan leaves the track: straight (unrouted) legs and legs much longer than the track between their points. */
fun legReport(track: List<GeoPoint>, pts: List<GeoPoint>, pr: PlannedRoute) {
    val G = com.onpath.app.data.Geo
    val along = G.cumulative(track)
    var k = 0
    val at = pts.map { p -> var best = k; for (j in k until minOf(track.size, k + 4000)) if (G.distance(track[j], p) < G.distance(track[best], p)) best = j; k = best; best }
    var straightKm = 0.0; var extraKm = 0.0
    val rows = ArrayList<String>()
    pr.legs.forEachIndexed { i, l ->
        val routed = G.length(l.points) / 1000; val onTrack = (along[at[i + 1]] - along[at[i]]) / 1000
        if (l.straight) straightKm += routed
        if (l.straight || routed > onTrack + 1.0) {
            extraKm += maxOf(0.0, routed - onTrack)
            rows += "      leg %d→%d %s: %.1f km routed, %.1f km on the track, at %.5f,%.5f".format(i + 1, i + 2, if (l.straight) "STRAIGHT" else "detour", routed, onTrack, pts[i].lat, pts[i].lon)
        }
    }
    val near = track.filterIndexed { i, _ -> i % 5 == 0 }.count { G.distanceToLine(it.lat, it.lon, pr.legs.flatMap { it.points }) <= 40 }
    println("   vs the track: %.1f %% of the track within 40 m of the route; %.1f km straight legs; %d legs > 1 km longer than the track (+%.1f km)".format(
        near * 100.0 / (track.size / 5 + 1), straightKm, rows.size, extraKm))
    if (System.getenv("MD_DUMP") == "1") rows.forEach(::println)
}
