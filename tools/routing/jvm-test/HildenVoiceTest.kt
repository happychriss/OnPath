// The owner's Hilden demo tour (2026-11-01, "keine einzige Angabe passte"): through Hilden's Stadtpark on the bike,
// rebuilt from three route points. Real BRouter hints → JogRule / CueRule → VoiceGuide sentences. Pass / fail:
// no cue and no screen turn at the pedestrian crossing of Benrather Straße (a sideways step = straight on), never "track" (Feldweg) where
// no highway=track is, the park's cycle path named. Run: KTTEST=.../HildenVoiceTest.kt run.sh <segmentsDir with E5_N50.rd5>
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
    val r = eng.route(listOf(GeoPoint(51.167810, 6.925827), GeoPoint(51.166511, 6.927717), GeoPoint(51.165892, 6.928305)),
        Activity.BIKE_TOURING, 2)
    val cum = Geo.cumulative(r.points)
    val hintAt = r.points.indices.filter { r.points[it].turn != null }
    val needed = JogRule.needed(r.points, hike = false)
    val fails = ArrayList<String>()
    val crossing = GeoPoint(51.167319, 6.927415)
    hintAt.forEachIndexed { k, i ->
        val p = r.points[i]
        val onto = VoiceGuide.ontoAt(r.points, cum, i, true)
        println("%5.0f m  %-12s %6.1f°  %-5s onto %-8s way %s".format(cum[i], p.turn, p.turnAngle, if (needed[k]) "CUE" else "quiet", onto, p.way?.wayType))
        if (needed[k] && Geo.distance(p, crossing) < 15) fails += "a cue at the crossing of Benrather Straße (${cum[i].toInt()} m)"
        if (onto == WayKind.TRACK) fails += "\"track\" at ${cum[i].toInt()} m (no highway=track in the park)"
    }
    // the screen's turn card / arrow (ScreenTurns: the same junctions as the voice) must not show the step either
    for (s in ScreenTurns.of(r.points, hike = false)) {
        println("SCREEN %5.0f m  %-13s %6.1f°".format(s.distanceFromStart, s.kind, s.angle))
        if (Geo.distance(s.point, crossing) < 15) fails += "the screen shows a turn at the crossing (${s.distanceFromStart.toInt()} m)"
    }
    val g = VoiceGuide()
    var t = 0L
    var m = 0.0
    val said = ArrayList<String>()
    while (m <= r.distanceM) {
        g.update(VoiceGuide.Input(t, r.points, 1, m, true, 5.5, true))?.let { said += VoiceText.of(it); println("SAY %5.0f m  %s".format(m, VoiceText.of(it))) }
        m += 5.5; t += 1000
    }
    if (said.none { it.contains("cycle path") }) fails += "the park's cycle path (bicycle=designated) not named"
    println(if (fails.isEmpty()) "PASS – ${said.size} sentences" else "FAIL – " + fails.joinToString("; "))
    if (fails.isNotEmpty()) System.exit(1)
}
