package com.onpath.web

import com.onpath.app.data.AppSettings
import com.onpath.app.routing.Activity
import com.onpath.app.routing.BRouterEngine
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanPoints
import com.onpath.app.routing.PlanResult
import com.onpath.app.time.TimeEstimate
import com.onpath.app.ui.PlanGaps
import com.onpath.app.ui.RouteWarnings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * The app side of the plan parity test (web/test/plans.json, web/app/test/plan-parity.mjs): every case planned the way
 * the phone's plan sheet does it – the app's classes only, no web code: [BRouterEngine] on the full rd5 files,
 * [PlanPoints] for the edits (Points list ✕ / drag), [PlanResult.plan] with the app's default pace
 * ([TimeEstimate.planFactors] of a fresh [AppSettings]) – exactly what AppViewModel.recalc calls. The Node side runs the
 * same cases through the Wasm engine's WebApi (edit + plan) with partial rd5 like the browser and compares.
 * Args: <plans.json> <segmentsDir> <out.json>
 */
fun main(args: Array<String>) {
    val (casesFile, segDir, outFile) = args
    val app = File(System.getProperty("onpath.root") ?: "../..").canonicalFile
    android.content.res.AssetManager.root = File(app, "app/src/main/assets")
    val files = Files.createTempDirectory("onpath-parity").toFile()
    val engine = BRouterEngine(PhoneContext(files), File(segDir).canonicalFile)
    val cases = JSONArray(File(casesFile).readText())
    val out = JSONArray()
    for (k in 0 until cases.length()) {
        val c = cases.getJSONObject(k)
        var pts = parse(c.getString("points"))
        val edits = c.optJSONArray("edits") ?: JSONArray()
        for (e in 0 until edits.length()) {
            val op = edits.getJSONArray(e)
            pts = when (op.getString(0)) {
                "delete" -> PlanPoints.deleteAt(pts, op.getInt(1))
                "move" -> PlanPoints.move(pts, op.getInt(1), op.getInt(2))
                else -> error("unknown edit ${op.getString(0)}")
            }
        }
        val act = Activity.valueOf(c.getString("activity"))
        val sac = c.getInt("sac")
        val pins = (c.optJSONArray("pinned") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
        val names = (c.optJSONObject("names") ?: JSONObject()).let { o -> o.keySet().associate { it.toInt() to o.getString(it) } }
        val t0 = System.currentTimeMillis()
        val res = PlanResult.plan(engine, pts, act, sac, pins, names.keys, TimeEstimate.planFactors(act, AppSettings()))
        val r = summary(res, names)
        println("${c.getString("name")}: ${r.optString("error").ifEmpty { "${r.getDouble("distanceM")} m, ${r.getDouble("timeS")} s, moved ${r.getBoolean("moved")}, gaps ${r.getJSONArray("gaps")}" }} (${System.currentTimeMillis() - t0} ms)")
        out.put(r)
    }
    File(outFile).writeText(out.toString(1))
}

/** The plan in the comparison's terms (plan-parity.mjs reads the web's plan JSON into the same shape). */
private fun summary(res: PlanResult, names: Map<Int, String>): JSONObject = JSONObject().apply {
    val p = res.route
    put("error", p.error ?: JSONObject.NULL)
    put("distanceM", p.distanceM); put("ascentM", p.ascentM); put("descentM", p.descentM); put("timeS", p.durationS)
    put("points", JSONArray(res.points.map { JSONArray(listOf(it.lat, it.lon)) }))
    put("moved", res.moved)
    // the line as the page draws it: the legs one after another (a leg join appears in both legs)
    put("line", JSONArray(p.legs.flatMap { it.points }.map { JSONArray(listOf(it.lat, it.lon)) }))
    put("straight", JSONArray(p.legs.map { it.straight }))
    put("gaps", JSONArray(res.gaps.map { it.index }))
    put("gapText", PlanGaps.text(res.gaps, res.activity) { names[it] } ?: JSONObject.NULL)
    put("warning", RouteWarnings.text(res.spots, res.sac) ?: JSONObject.NULL)
    put("spots", JSONArray(res.spots.map { RouteWarnings.rowTitle(it, res.sac) }))
    put("difficulty", res.rating.text)
    put("because", res.rating.because ?: JSONObject.NULL)
    put("profile", res.profile?.let { d -> JSONObject().put("n", d.dist.size).put("min", d.min).put("max", d.max).put("total", d.total)
        .put("steep", JSONArray(d.steepness().map { it.ordinal })) } ?: JSONObject.NULL)
}

private fun parse(s: String): List<GeoPoint> = s.split(';').filter { it.isNotBlank() }.map { p ->
    val (la, lo) = p.split(',').map { it.trim().toDouble() }
    GeoPoint(la, lo)
}

/** The phone's Context as far as BRouterEngine reads it (filesDir for the profiles, the app's assets). */
private class PhoneContext(private val dir: File) : android.content.Context() {
    override fun getApplicationContext(): android.content.Context = this
    override fun getFilesDir(): File = dir
    override fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
}
