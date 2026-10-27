package com.onpath.web

import java.io.File

/**
 * Builds a small rd5 that holds only some 1° parts, with the same code the phone and the browser use
 * ([Engine.putLayout] / [Engine.putPart] → Rd5Parts.buildIndex / buildTail). For previews and tests that cannot
 * carry whole rd5 files. Args: tile (e.g. E5_N50.rd5), dir with head.bin (first 200 bytes), tail.bin (bytes after the
 * last part) and part<i>.bin files, output file.
 */
fun main(args: Array<String>) {
    val (tile, dir, out) = args
    val d = File(dir)
    val tmp = createTempDir("rd5")
    Engine.init(tmp.path)
    Engine.putLayout(tile, File(d, "head.bin").readBytes(), File(d, "tail.bin").readBytes())
    d.listFiles { f -> f.name.matches(Regex("part\\d+\\.bin")) }!!.sortedBy { it.name }.forEach { f ->
        val i = f.name.removePrefix("part").removeSuffix(".bin").toInt()
        check(Engine.putPart(tile, i, f.readBytes())) { "part $i does not fit the index" }
        println("part $i: ${f.length()} bytes")
    }
    Engine.writeParts()
    File(tmp, "segments/$tile").copyTo(File(out), overwrite = true)
    println("wrote $out (${File(out).length()} bytes)")
}
