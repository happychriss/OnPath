// A GPX track as a plan with few route points (routing/TrackToPlan) on real data: points, how well the plan follows
// the track, routing calls, time. Run: TRACK_GPX=<ride.gpx> KTTEST=tools/routing/jvm-test/TrackToPlanJvm.kt run.sh <segmentsDir>
// (the GPX stays outside the repo – personal data). Owner ride 2026-10-28: 2565 points → 7 route points, 99 %, 1.3 s.
import com.onpath.app.routing.*
import java.io.File

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    val gpxFiles = (System.getenv("TRACK_GPX") ?: error("TRACK_GPX=<file.gpx>[:<file2.gpx>] needed")).split(":")
    for (f in gpxFiles) {
        val xml = File(f).readText()
        val pts = Regex("<trkpt lat=\"([-\\d.]+)\" lon=\"([-\\d.]+)\"").findAll(xml).map { GeoPoint(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }.toList()
        val t0 = System.currentTimeMillis()
        val r = TrackToPlan.fit(pts, eng, Activity.BIKE_TOURING, 2)
        val legs = r.points.zipWithNext().map { (a, b) -> try { eng.route(listOf(a, b), Activity.BIKE_TOURING, 2).points } catch (e: Exception) { println("  leg fails: $a → $b"); listOf(a, b) } }
        val planPts = legs.flatten()
        val plan = object { val points = planPts; val distanceM = com.onpath.app.data.Geo.length(planPts) }
        val far = pts.filterIndexed { i, _ -> i % 5 == 0 }.count { com.onpath.app.data.Geo.distanceToLine(it.lat, it.lon, plan.points) > 40 }
        r.points.forEach { println("  point %.6f,%.6f".format(it.lat, it.lon)) }
        println("%s: %d track points -> %d route points, follows %.0f %%, %d calls, %d failed legs, %.1f s; final plan %.1f km (track %.1f km), %d of %d samples > 40 m off".format(
            File(f).name, pts.size, r.points.size, r.follows * 100, r.calls, r.failedLegs, (System.currentTimeMillis() - t0) / 1000.0,
            plan.distanceM / 1000, com.onpath.app.data.Geo.length(pts) / 1000, far, pts.size / 5))
    }
}
