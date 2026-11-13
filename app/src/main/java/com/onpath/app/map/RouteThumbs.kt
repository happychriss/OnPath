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
     * The key of [t]'s line: [points] = the stored points' summary ("count:sumLat:sumLon" from TourDb.lineKeys, null = no
     * stored points → its via points / waypoints count, as the list's line does). The kind is in it (its line colour).
     */
    fun key(t: Tour, points: String?): String {
        val line = points ?: t.waypoints.let { w ->
            "w${w.size}:" + "%.6f".format(java.util.Locale.ROOT, w.sumOf { it.lat }) + ":" + "%.6f".format(java.util.Locale.ROOT, w.sumOf { it.lon })
        }
        return "v$VERSION ${t.kind.name} $line"
    }

    /**
     * The tours whose picture is missing or shows another line, in the order to make them: [first] (just saved / ended)
     * first, then newest first. A tour being recorded, one without a uuid, and keys in [skip] (failed in this run) wait.
     */
    fun todo(tours: List<Tour>, keys: Map<Long, String>, stored: (String) -> String?, skip: Set<String> = emptySet(),
             first: String? = null): List<Pair<Tour, String>> =
        tours.asSequence().filter { it.state == "done" && it.uuid.isNotEmpty() }
            .map { it to key(it, keys[it.id]) }
            .filter { (t, k) -> k !in skip && stored(t.uuid) != k }
            .sortedWith(compareBy<Pair<Tour, String>>({ it.first.uuid != first }, { -it.first.createdMs }))
            .toList()
}
