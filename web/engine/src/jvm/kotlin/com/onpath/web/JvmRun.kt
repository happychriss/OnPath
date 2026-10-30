package com.onpath.web

import java.io.File
import java.nio.file.Files

/**
 * The JVM side of the comparison (web/test): the same [Engine] code as the browser, on a normal JVM with the full
 * rd5 file from disk (like tools/routing/jvm-test). Writes one JSON line per case.
 * Args: <cases.json> <segmentsDir> <out.json>
 */
fun main(args: Array<String>) {
    val (casesFile, segDir, outFile) = args
    val root = Files.createTempDirectory("onpath-web-jvm").toFile()
    val app = File(System.getProperty("onpath.root") ?: "../..").canonicalFile
    Files.createSymbolicLink(File(root, "assets").toPath(), File(app, "app/src/main/assets").toPath())
    Files.createSymbolicLink(File(root, "segments").toPath(), File(segDir).canonicalFile.toPath())
    Engine.init(root.path)
    val cases = Regex("""\{"name":\s*"([^"]*)",\s*"points":\s*"([^"]*)",\s*"activity":\s*"([^"]*)",\s*"sac":\s*(\d+)\}""")
        .findAll(File(casesFile).readText()).toList()
    File(outFile).printWriter().use { out ->
        out.println("[")
        cases.forEachIndexed { i, m ->
            val (name, pts, act, sac) = m.destructured
            val r = Engine.route(pts, act, sac.toInt())
            println("$name / $act: " + r.take(120))
            out.println((if (i > 0) "," else "") + r)
        }
        out.println("]")
    }
    // pace setting (WebPace): per chosen case the plan time at every level – browser-compare checks the browser
    // gives the same numbers (owner 2026-10-29: "changing pace changes the time exactly as the JVM computes")
    File(File(outFile).absoluteFile.parentFile, "jvm-pace.json").writeText(
        paceRun(cases.map { m -> m.destructured.let { (n, p, a, s) -> PaceCase(n, p, a, s.toInt()) } }))
}

data class PaceCase(val name: String, val points: String, val activity: String, val sac: Int)

/** Hike, a bike and an e-bike (the flat cap): the first case of each. Keep in step with web/app/src/compare.ts. */
val PACE_ACTIVITIES = listOf("HIKE", "BIKE_TREKKING", "EBIKE")

/**
 * For each chosen case: plan at Average, then [WebPace.planTime] at every level, then a new plan at Very fit (its
 * time must equal planTime's). JSON array; the browser builds the same from the same engine calls.
 */
fun paceRun(cases: List<PaceCase>): String = PACE_ACTIVITIES.mapNotNull { a -> cases.firstOrNull { it.activity == a } }
    .joinToString(",\n", "[", "]") { c ->
        WebPace.set("AVERAGE", "AVERAGE")
        Engine.plan(c.points, c.activity, c.sac)
        val levels = com.onpath.app.time.Pace.entries.joinToString(",", "{", "}") { p ->
            WebPace.set(p.name, p.name)
            "\"" + p.name + "\":" + WebPace.planTime(c.points, c.activity, c.sac)
        }
        val fit = Regex("\"timeS\":([0-9.]+)").find(Engine.plan(c.points, c.activity, c.sac))?.groupValues?.get(1) ?: "null"
        WebPace.set("AVERAGE", "AVERAGE")
        "{\"name\":\"" + c.name + "\",\"activity\":\"" + c.activity + "\",\"levels\":" + levels +
            ",\"planVeryFitS\":" + fit + "}"
    }
