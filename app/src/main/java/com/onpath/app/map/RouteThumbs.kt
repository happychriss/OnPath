package com.onpath.app.map

import com.onpath.app.data.AtomicFiles
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
