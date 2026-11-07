// Field bug owner 2026-11-07 12:30 (Hilden bike plan, "warum keine Ansage, ich bin auf einer anderen Straße"): right by
// 82.5° from a quiet street (class 6) onto a road (class 24) at 51.169673, 6.926565, 19 m along it, left by 88.2° onto a
// footway – the jog rule set both quiet (no voice, no vibration, no turn card). The owner's plan rebuilt from its four
// route points; real BRouter hints → JogRule / CueRule → ScreenTurns / VoiceGuide. Pass / fail: a bike cue, a screen turn
// and a sentence within 15 m of that right turn; the 18 m crossing piece at 494 / 512 m stays one quiet step and the right
// after it (529 m) a cue. DUMP=1 also lists the hike. Run: KTTEST=.../JogHildenTest.kt run.sh <segmentsDir with E5_N50.rd5>
import com.onpath.app.data.Geo
import com.onpath.app.navigation.*
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
    val wps = listOf(GeoPoint(51.16928899999999, 6.925429000000008), GeoPoint(51.16915399999999, 6.929327999999998),
        GeoPoint(51.16826, 6.93204), GeoPoint(51.16830975821202, 6.932520568370819))
    val offset = GeoPoint(51.169673, 6.926565)                                      // the right onto the road
    val crossing = listOf(GeoPoint(51.169562, 6.930661), GeoPoint(51.169426, 6.930797)) // the 18 m crossing piece
    val afterCrossing = GeoPoint(51.169515, 6.930992)                                // the right after it
    val fails = ArrayList<String>()
    val acts = if (System.getenv("DUMP") != null) listOf(Activity.BIKE_TOURING, Activity.HIKE) else listOf(Activity.BIKE_TOURING)
    for (act in acts) {
        val bike = act != Activity.HIKE
        val pr = Planner(eng).plan(wps, act, 1, setOf(2, 3))
        val pts = pr.legs.flatMap { it.points }
        println("=== $act  ${pts.size} points, ${pr.distanceM.toInt()} m")
        val line = JogRule.approach(pts, hike = !bike)
        val cum = Geo.cumulative(line)
        val idx = line.indices.filter { line[it].turn != null }
        val needed = JogRule.needed(line, cum, idx, hike = !bike)
        idx.forEachIndexed { k, i ->
            val p = line[i]; val raw = pts[i]
            val cr = CueRule.needed(p.turn!!, p.shape, !bike)
            println("%3d %5.0f m %.6f,%.6f raw %-12s %6.1f -> %-12s %6.1f  shape=%s  CueRule=%-5s final=%s way=%s".format(i, cum[i], p.lat, p.lon,
                raw.turn, raw.turnAngle, p.turn, JogRule.angleOf(p), p.shape?.encode(), cr, if (needed[k]) "CUE" else "quiet", p.way?.wayType))
            if (!bike) return@forEachIndexed
            val near = { q: GeoPoint -> Geo.distance(p, q) < 15 }
            if (near(offset) && !needed[k]) fails += "the right onto the road (${cum[i].toInt()} m) is quiet"
            if (crossing.any(near) && needed[k]) fails += "a cue on the crossing piece (${cum[i].toInt()} m)"
            if (near(afterCrossing) && !needed[k]) fails += "the right after the crossing piece (${cum[i].toInt()} m) is quiet"
        }
        val screen = ScreenTurns.of(pts, hike = !bike)
        for (s in screen) println("SCREEN %5.0f m  %-13s %6.1f°".format(s.distanceFromStart, s.kind, s.angle))
        if (bike && screen.none { Geo.distance(it.point, offset) < 15 }) fails += "no turn card at the right onto the road"
        val offsetM = idx.firstOrNull { Geo.distance(line[it], offset) < 15 }?.let { cum[it] }
        val g = VoiceGuide()
        var t = 0L; var m = 0.0
        val sp = if (bike) 5.5 else 1.4
        val total = cum.last()
        var rightSaid = false
        while (m <= total) {
            g.update(VoiceGuide.Input(t, pts, 1, m, true, sp, bike))?.let { ph ->
                println("SAY %5.0f m  %s".format(m, VoiceText.of(ph)))
                if (ph is Phrase.Junction && ph.inM == 0.0 && ph.turn == Turn.RIGHT && offsetM != null && offsetM - m in 0.0..200.0) {
                    rightSaid = true
                    if (ph.onto != null) fails += "the right onto the road said \"${VoiceText.of(ph)}\" (the way after the next turn)"
                }
            }
            m += sp; t += 1000
        }
        if (bike && !rightSaid) fails += "the right onto the road never said"
    }
    println(if (fails.isEmpty()) "PASS" else "FAIL – " + fails.joinToString("; "))
    if (fails.isNotEmpty()) System.exit(1)
}
