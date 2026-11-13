package com.onpath.app.map

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import com.onpath.app.data.Tour
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.routing.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Makes My routes' map pictures ([RouteThumbs], owner 2026-11-13 L1) in the background: one at a time, a pause between
 * two, never while [busy] (navigating or recording – the snapshot shares the GPU with the live map). [kick] after a save /
 * overwrite, a tour's end, a sync, an import, a restore and when My routes opens: the pass finds what is missing or shows
 * an old line ([ThumbRules.todo]) and makes only that – a route saved on the phone, one from the browser, a GPX file, a
 * restored tour and the tours from before this version alike. Never blocks saving or ending a tour; a failure is an INFO
 * and the row keeps its drawn line (tried again at the next app start).
 */
class ThumbMaker(
    private val context: Context,
    private val thumbs: RouteThumbs,
    private val scope: CoroutineScope,
    /** Every tour in My routes and the recycle bin's uuids (the bin keeps its pictures: restored, it shows at once). */
    private val tours: () -> Pair<List<Tour>, Set<String>>,
    /** TourDb.lineKeys. */
    private val lineKeys: () -> Map<Long, String>,
    /** The tour's line as My routes draws it. */
    private val line: (Tour) -> List<List<GeoPoint>>,
    /** The map style for a snapshot (offline cells + overview); null = no map now (nothing made, tried again later). */
    private val style: suspend () -> String?,
    /** The line's colour (ARGB) of a tour. */
    private val color: (Tour) -> Int,
    /** Navigating or recording: no picture now. */
    private val busy: () -> Boolean,
    /** A picture was stored (main thread is not promised): My routes reads it. */
    private val made: () -> Unit,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var again = false
    @Volatile private var first: String? = null
    /** Keys that failed in this run (no second 15 s wait for the same line until the next start). */
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** The time of the last pictures (ms each, newest last) – diagnostics. */
    val timings: MutableList<Long> = Collections.synchronizedList(ArrayList())

    /** Look for pictures to make; [firstUuid] = the tour just saved / ended goes first. Returns at once. */
    fun kick(firstUuid: String? = null) {
        if (firstUuid != null) first = firstUuid
        if (!running.compareAndSet(false, true)) { again = true; return }
        scope.launchSafe("routes", "Map pictures of My routes", Dispatchers.IO) {
            try {
                do { again = false; pass() } while (again)
            } finally { running.set(false) }
        }
    }

    private suspend fun pass() {
        if (busy()) return
        val (list, binned) = tours()
        thumbs.retain(list.mapTo(HashSet()) { it.uuid } + binned)
        val todo = ThumbRules.todo(list, lineKeys(), thumbs::storedKey, failed, first)
        first = null
        if (todo.isEmpty()) return
        val json = style() ?: return
        val density = context.resources.displayMetrics.density
        val px = (ThumbRules.TILE_DP * density).toInt()
        val pad = (ThumbRules.PAD_DP * density).toInt()
        for ((t, key) in todo) {
            if (busy()) { Problems.trace("routes", "Map pictures wait: a tour runs (${todo.size} to make)"); return }
            val segs = line(t).filter { it.size >= 2 }
            if (segs.isEmpty()) { thumbs.write(t.uuid, key, null); continue } // nothing to draw: the row's sign stays
            val t0 = SystemClock.elapsedRealtime()
            val shot = RouteSnapshot.make(context, json, segs, color(t), px, px, pad, density,
                lineScale = ThumbRules.LINE_SCALE)
            val ms = SystemClock.elapsedRealtime() - t0
            if (shot == null) {
                failed += key
                Problems.info("routes", "Map picture of a route not made (${ms} ms) – its row keeps the drawn line")
                continue
            }
            val bytes = try { encode(shot.bitmap) } catch (e: Exception) {
                failed += key; Problems.info("routes", "Map picture of a route not encoded – its row keeps the drawn line", e); null
            } finally { shot.bitmap.recycle() }
            if (bytes != null && thumbs.write(t.uuid, key, bytes)) {
                timings += ms
                while (timings.size > 20) timings.removeAt(0)
                Problems.trace("routes", "Map picture made in $ms ms (${bytes.size / 1024} KB, ${segs.sumOf { it.size }} points)")
                made()
            }
            delay(PAUSE_MS) // low priority: the app's own work goes first
        }
    }

    private fun encode(b: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        if (!b.compress(format, QUALITY, out)) error("compress refused")
        out.toByteArray()
    }

    companion object {
        const val PAUSE_MS = 400L
        const val QUALITY = 82
    }
}
