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
}
