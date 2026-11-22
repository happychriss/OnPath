import com.onpath.app.routing.*
import com.onpath.app.time.Factors
import java.io.File
import kotlin.math.abs

// The detour hint on real BRouter data (owner 2026-11-21, „Hinweis, du entscheidest“, Meran, needs E10_N45.rd5), pass/fail:
//   C=core/src/main/kotlin/com/onpath/app
//   KTTEST=$PWD/tools/routing/jvm-test/DetourHintJvmTest.kt
//     EXTRA_KT="$PWD/$C/routing/PlanDetour.kt $PWD/$C/routing/PlanResult.kt $PWD/$C/routing/ProfileData.kt
//     $PWD/$C/routing/PlanReach.kt $PWD/$C/domain/PlanGaps.kt" JAVA_XMX=1g tools/routing/jvm-test/run.sh <segmentsDir>
// The real Planner's route, then the real PlanDetour.find. The hint shows only where the T level causes the detour: never on
// a summit planned as a spur, never where the stretch is as long without the point, never beside a point the route does
// not reach (that red line stays: Vellau → Mutkopf → Leiteralm at T1). The route is the same before and after.

class FakeCtx(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}

/** [metres] = the hint expected at point ②, null = none. */
class Case(val name: String, val points: List<GeoPoint>, val sac: Int, val metres: Double?)

fun main() {
    android.content.res.AssetManager.root = File("/home/user/onpath/app/src/main/assets")
    val files = File("/home/user/build/routing-test/kt-files").apply { deleteRecursively(); mkdirs() }
    val eng = BRouterEngine(FakeCtx(files), File("/home/user/build/routing-test/segments"))
    fun p(lat: Double, lon: Double) = GeoPoint(lat, lon)
    val vellau = p(46.69560, 11.10935); val mutkopf = p(46.71077, 11.13787); val leiteralm = p(46.70605, 11.11125)
    val cases = listOf(
        Case("the level causes it: 7.3 km at T1, 4.5 km at T2, the neighbours 1.4 km at both",
            listOf(p(46.68895, 11.18446), p(46.70009, 11.17894), p(46.69748, 11.18970)), 1, 2810.0),
        Case("the same way at T1 and T2", listOf(p(46.66341, 11.14011), p(46.64434, 11.14674), p(46.64303, 11.17326)), 1, null),
        Case("the stretch is as long at T1 without the point",
            listOf(p(46.67483, 11.12594), p(46.66383, 11.09826), p(46.66198, 11.10452)), 1, null),
        Case("a summit on purpose: Vellau → Mutkopf → Leiteralm at T2", listOf(vellau, mutkopf, leiteralm), 2, null),
        Case("a point not reached keeps its own red line: the same plan at T1", listOf(vellau, mutkopf, leiteralm), 1, null),
    )
    var failed = 0
    for (c in cases) {
        val res = PlanResult.plan(eng, c.points, Activity.HIKE, c.sac, emptySet(), emptySet(), Factors())
        val line = res.route.points.toList()
        val found = PlanDetour.find(eng, res)
        val ok = line == res.route.points && (if (c.metres == null) found.isEmpty()
            else found.singleOrNull()?.let { it.index == 1 && abs(it.extraM - c.metres) < 100 } == true)
        println("%s  %s: T%d %.1f km, %d gap(s), hint %s".format(if (ok) "ok  " else "FAIL", c.name, c.sac, res.route.distanceM / 1000,
            res.gaps.size, found.map { "② %.0f m".format(it.extraM) }))
        if (!ok) failed++
    }
    println(if (failed == 0) "PASS" else "FAIL: $failed")
    if (failed > 0) System.exit(1)
}
