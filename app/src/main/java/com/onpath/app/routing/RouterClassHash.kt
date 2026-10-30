package com.onpath.app.routing

import java.security.MessageDigest

/**
 * Hash of the routing profiles the map's router classes are made with (docs/map-style-notes.md "Router classes"): the
 * same value as `tools/routing/wayclass/WayClass.java` `hash()` – the cell manifest's `wayclass` – computed here from
 * the profiles this app routes with. Equal = the map's green / forbidden ways come from the same profiles as the
 * routes; different = the cell was built with other profiles ([com.onpath.app.cells.RouterClassCheck]).
 *
 * Format 2: "2-" + the first 16 hex digits of sha256 over the classifier [VERSION], its [GOOD] thresholds (Java
 * `Arrays.toString(float[])`) and, per file of [FILES], the name + its [normalize]d text – comments and blank lines do
 * not count. Format 1 (16 hex digits over the raw bytes, cells built before) is not comparable: [isComparable] false.
 * `RouterClassHashTest` keeps all of it in step with WayClass.java (compiles it and compares).
 */
object RouterClassHash {
    /** WayClass.VERSION. */
    const val VERSION = "wayclass-5"
    /** WayClass.GOOD as Java's Arrays.toString writes it. */
    const val GOOD = "[1.4, 1.2, 1.2, 7.0]"
    /** The files WayClass hashes, in its order. */
    val FILES = listOf("lookups.dat", "hiking-mountain.brf", "trekking.brf", "touring.brf", "mtb.brf")

    private val FORMAT_2 = Regex("^2-[0-9a-f]{16}$")

    /** A hash of the format this app computes; anything else (format 1, garbage) is unknown, never "different". */
    fun isComparable(hash: String?): Boolean = hash != null && FORMAT_2.matches(hash)

    /** The hash of the profiles [read] gives by file name. */
    fun of(read: (String) -> ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(VERSION.toByteArray(Charsets.UTF_8))
        md.update(GOOD.toByteArray(Charsets.UTF_8))
        for (f in FILES) {
            md.update(f.toByteArray(Charsets.UTF_8))
            md.update(normalize(f, read(f)))
        }
        return "2-" + md.digest().joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    /**
     * A profile file without what BRouter ignores (WayClass.normalize, same rules): per line the tokens separated by
     * space / tab / CR / FF; in a .brf a comment starts at a token beginning with '#', in lookups.dat only a whole line
     * starting with '#' is one; lines without tokens go. A .brf comment naming "noStartWay" is kept (BRouter reads it).
     * Lines joined by single spaces, each ended by "\n"; bytes as ISO-8859-1 (1:1).
     */
    fun normalize(name: String, bytes: ByteArray): ByteArray {
        val brf = name.endsWith(".brf")
        val out = StringBuilder(bytes.size)
        for (line in String(bytes, Charsets.ISO_8859_1).split('\n')) {
            val tokens = ArrayList<String>()
            var i = 0
            while (i < line.length) {
                while (i < line.length && blank(line[i])) i++
                val s = i
                while (i < line.length && !blank(line[i])) i++
                if (i > s) tokens += line.substring(s, i)
            }
            var cut = tokens.indices.firstOrNull { t -> tokens[t].startsWith("#") && (brf || t == 0) } ?: tokens.size
            if (cut < tokens.size && brf && tokens.subList(cut, tokens.size).joinToString(" ").contains("noStartWay")) cut = tokens.size
            if (cut == 0) continue
            out.append(tokens.subList(0, cut).joinToString(" ")).append('\n')
        }
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun blank(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\u000C'
}
