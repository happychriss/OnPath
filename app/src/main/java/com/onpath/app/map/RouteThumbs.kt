package com.onpath.app.map

import com.onpath.app.data.AtomicFiles
import com.onpath.app.data.Tour
import com.onpath.app.debug.Problems
import java.io.File

/**
 * The small map pictures of My routes' rows (owner 2026-11-13, layout L1: a square map + the route left of the name).
 * One file per tour, by uuid, under the app's files (`files/thumbs/<uuid>.webp`, not in any backup – they are made
 * again), plus `<uuid>.key`: the line the picture shows ([ThumbRules.key]). A picture whose line changed (a route saved
 * over, routed again after a sync) is made anew; a tour deleted for good loses its picture. Reading never throws.
 */
class RouteThumbs(val dir: File) {
    fun file(uuid: String) = File(dir, "$uuid.webp")
    private fun keyFile(uuid: String) = File(dir, "$uuid.key")

    /** The line key the stored picture was made for; null = none made yet. */
    fun storedKey(uuid: String): String? = try {
        keyFile(uuid).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) { Problems.info("routes", "Thumbnail key of $uuid unreadable – made again", e); null }

    /** Stores the picture [bytes] (null = nothing to draw: the row keeps its drawn line) for line [key]. False on failure (reported). */
    fun write(uuid: String, key: String, bytes: ByteArray?): Boolean = try {
        dir.mkdirs()
        if (bytes == null) file(uuid).delete() else AtomicFiles.writeBytes(file(uuid), bytes)
        AtomicFiles.writeText(keyFile(uuid), key)
        true
    } catch (e: Exception) { Problems.info("routes", "Thumbnail of $uuid not stored – the row keeps its drawn line", e); false }

    /** Only the key changes (the line is the same, its signature moved on): the picture stays. False on failure (reported). */
    fun writeKey(uuid: String, key: String): Boolean = try {
        dir.mkdirs(); AtomicFiles.writeText(keyFile(uuid), key); true
    } catch (e: Exception) { Problems.info("routes", "Thumbnail key of $uuid not stored – looked at again next time", e); false }

    /** Removes the pictures of tours no longer on the phone ([keep] = uuids still there, recycle bin included). */
    fun retain(keep: Set<String>): Int {
        val files = dir.listFiles() ?: return 0
        var n = 0
        for (f in files) {
            val uuid = f.name.substringBeforeLast('.')
            if (uuid !in keep && (f.name.endsWith(".webp") || f.name.endsWith(".key") || f.name.endsWith(".tmp"))) { if (f.delete()) n++ }
        }
        return n
    }
}

/** Which pictures to make, and the key of a line (JVM tested). */
object ThumbRules {
    /** Raised when the picture's look changes (size, line width): every picture is made again once. */
    const val VERSION = 1

    /** The edge of a picture (dp): the L1 tile. */
    const val TILE_DP = 84
    /** The room between the route and the picture's edge (dp): the casing stays inside. */
    const val PAD_DP = 10
    /** The route line on the small picture against the map's ([RouteLook]): 3.6 dp in a 6 dp casing on 84 dp. */
    const val LINE_SCALE = 0.6f

    /**
     * The cheap signature of [t]'s line from its row alone (kind, distance, ascent, the via points / waypoints) – no
     * database work. A line that changes (a route saved over, routed again after a sync, a track replaced) changes it.
     */
    fun sig(t: Tour): String {
        val w = t.waypoints
        return "v$VERSION ${t.kind.name} " + "%.1f".format(java.util.Locale.ROOT, t.distanceM) + " " +
            "%.1f".format(java.util.Locale.ROOT, t.ascentM) + " w${w.size}:" +
            "%.6f".format(java.util.Locale.ROOT, w.sumOf { it.lat }) + ":" + "%.6f".format(java.util.Locale.ROOT, w.sumOf { it.lon })
    }

    /**
     * The key a picture is made for: "[sig]|line" – line = the stored points' summary ("count:sumLat:sumLon", TourDb.lineKey;
     * null = no stored points → its via points, as the list's line does).
     */
    fun key(t: Tour, points: String?): String = sig(t) + "|" + (points ?: "none")

    /** What a pass does: [make] these pictures (in this order), only [rekey] these (same line, new signature); [queried] = line keys read. */
    data class Plan(val make: List<Pair<Tour, String>>, val rekey: List<Pair<Tour, String>>, val queried: Int)

    /**
     * A pass over My routes' [tours]: a tour whose stored key ([stored], by uuid) has its current [sig] needs nothing – no
     * database work; only for the others the line key is read ([lineKey]) – a new line → a picture, the same line → the key
     * only. [first] (just saved / ended) first, then newest first. A tour being recorded, one without a uuid, and keys in
     * [skip] (failed in this run) wait.
     */
    fun plan(tours: List<Tour>, stored: (String) -> String?, lineKey: (Tour) -> String?, skip: Set<String> = emptySet(),
             first: String? = null): Plan {
        val make = ArrayList<Pair<Tour, String>>(); val rekey = ArrayList<Pair<Tour, String>>(); var queried = 0
        for (t in tours) {
            if (t.state != "done" || t.uuid.isEmpty()) continue
            val have = stored(t.uuid)
            val sig = sig(t)
            if (have != null && have.substringBefore('|') == sig) continue
            queried++
            val key = key(t, lineKey(t))
            if (key in skip) continue
            if (have != null && have.substringAfter('|', "") == key.substringAfter('|')) rekey += t to key else make += t to key
        }
        make.sortWith(compareBy<Pair<Tour, String>>({ it.first.uuid != first }, { -it.first.createdMs }))
        return Plan(make, rekey, queried)
    }
}
