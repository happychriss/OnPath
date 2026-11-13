package com.onpath.app.map

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.onpath.app.data.Tour
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.routing.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Makes My routes' map pictures ([RouteThumbs], owner 2026-11-13 L1) in the background: one at a time, a pause between
 * two, never while [busy] (navigating or recording – the snapshot shares the GPU with the live map). [kick] with the list
 * My routes just read (after a save / overwrite, a tour's end, a sync, an import, a restore, My routes opening): a pass
 * compares each tour's cheap signature ([ThumbRules.sig], from the row – no database work) with the key its picture was
 * made for (kept in memory after the first read of its key file); only a tour whose signature changed costs one indexed
 * query for its line key ([lineKey]) and, when the line really changed, a picture. Never blocks saving or ending a tour;
 * a failure is an INFO and the row keeps its drawn line (tried again at the next app start).
 */
class ThumbMaker(
    private val thumbs: RouteThumbs,
    private val scope: CoroutineScope,
    /** The recycle bin's uuids (their pictures stay: restored, it shows at once) – read once per run for the clean-up. */
    private val binned: () -> Set<String>,
    /** TourDb.lineKey: the stored line's summary of one tour (null = no stored points). */
    private val lineKey: (Tour) -> String?,
    /** The tour's line as My routes draws it. */
    private val line: (Tour) -> List<List<GeoPoint>>,
    /** The map style for a snapshot (offline cells + overview); null = no map now (nothing made, tried again later). */
    private val style: suspend () -> String?,
    /** The picture of [line] on [style] as file bytes ([render]); null = not made (reported there). */
    private val snap: suspend (style: String, segments: List<List<GeoPoint>>, t: Tour) -> ByteArray?,
    /** Navigating or recording: no picture now. */
    private val busy: () -> Boolean,
    /** A picture was stored: My routes reads it. */
    private val made: () -> Unit,
    private val io: CoroutineContext = Dispatchers.IO,
    private val pauseMs: Long = PAUSE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var again = false
    @Volatile private var first: String? = null
    @Volatile private var latest: List<Tour> = emptyList()
    @Volatile private var cleaned = false
    /** uuid → the key its picture was made for ("" = none yet); filled from the key files once, then kept here. */
    private val keys = ConcurrentHashMap<String, String>()
    /** Keys that failed in this run (no second 15 s wait for the same line until the next start). */
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** The time of the last pictures (ms each, newest last) – diagnostics. */
    val timings: MutableList<Long> = Collections.synchronizedList(ArrayList())
    /** Line keys read from the database (tests, diagnostics: a pass over unchanged tours reads none). */
    @Volatile var lineQueries = 0
        private set
    val idle: Boolean get() = !running.get()

    /** Look for pictures to make among [tours] (My routes' list); [firstUuid] = just saved / ended goes first. Returns at once. */
    fun kick(tours: List<Tour>, firstUuid: String? = null) {
        latest = tours
        if (firstUuid != null) first = firstUuid
        if (!running.compareAndSet(false, true)) { again = true; return }
        scope.launchSafe("routes", "Map pictures of My routes", io) {
            try {
                do { again = false; pass(latest) } while (again)
            } finally { running.set(false) }
        }
    }

    private fun stored(uuid: String): String? = keys.getOrPut(uuid) { thumbs.storedKey(uuid) ?: "" }.takeIf { it.isNotEmpty() }

    private suspend fun pass(tours: List<Tour>) {
        if (busy()) return
        // once per run, and never on an empty list (not read yet – it would take every picture away)
        if (!cleaned && tours.isNotEmpty()) {
            cleaned = true
            thumbs.retain(tours.mapTo(HashSet()) { it.uuid } + binned())
        }
        val plan = ThumbRules.plan(tours, ::stored, { t -> lineQueries++; lineKey(t) }, failed, first)
        first = null
        for ((t, key) in plan.rekey) if (thumbs.writeKey(t.uuid, key)) keys[t.uuid] = key
        if (plan.make.isEmpty()) return
        val json = style() ?: return
        for ((t, key) in plan.make) {
            if (busy()) { Problems.trace("routes", "Map pictures wait: a tour runs (${plan.make.size} to make)"); return }
            val segs = line(t).filter { it.size >= 2 }
            if (segs.isEmpty()) { if (thumbs.write(t.uuid, key, null)) keys[t.uuid] = key; continue } // the row's sign stays
            val t0 = clock()
            val bytes = snap(json, segs, t)
            val ms = clock() - t0
            if (bytes == null) {
                failed += key
                Problems.info("routes", "Map picture of a route not made (${ms} ms) – its row keeps the drawn line")
                continue
            }
            if (thumbs.write(t.uuid, key, bytes)) {
                keys[t.uuid] = key
                timings += ms
                while (timings.size > 20) timings.removeAt(0)
                Problems.trace("routes", "Map picture made in $ms ms (${bytes.size / 1024} KB, ${segs.sumOf { it.size }} points)")
                made()
            }
            delay(pauseMs) // low priority: the app's own work goes first
        }
    }

    companion object {
        const val PAUSE_MS = 400L
        const val QUALITY = 82

        /** The picture of [segments] in [color] on [styleJson] as WebP bytes; null = the snapshot failed (reported). */
        suspend fun render(context: Context, styleJson: String, segments: List<List<GeoPoint>>, color: Int): ByteArray? {
            val density = context.resources.displayMetrics.density
            val px = (ThumbRules.TILE_DP * density).toInt()
            val region = ThumbRules.region(segments.flatten()) ?: return null
            // the room is in the region itself (square, larger by the margin and a marker) – no snapshot padding
            val shot = RouteSnapshot.make(context, styleJson, segments, color, px, px, 0, density,
                lineScale = ThumbRules.LINE_SCALE, region = region, withoutNetworks = true) ?: return null
            return try { encode(withEnds(shot, color, density)) } catch (e: Exception) {
                Problems.info("routes", "Map picture of a route not encoded – its row keeps the drawn line", e); null
            } finally { shot.bitmap.recycle() }
        }

        /**
         * Start and end on the picture (owner 2026-11-13): a dot in the line's colour at the start and the goal red, each
         * in a white ring – the signs of the rows' drawn line. Drawn on a copy the snapshot's bitmap can be read from.
         */
        private fun withEnds(shot: RouteSnapshot.Shot, color: Int, density: Float): Bitmap {
            val b = if (shot.bitmap.isMutable) shot.bitmap else shot.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val c = android.graphics.Canvas(b)
            val r = ThumbRules.MARKER_DP * density; val ring = ThumbRules.MARKER_RING_DP * density
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            fun dot(x: Float, y: Float, fill: Int) {
                paint.color = android.graphics.Color.WHITE; c.drawCircle(x, y, r + ring, paint)
                paint.color = fill; c.drawCircle(x, y, r, paint)
            }
            dot(shot.startX, shot.startY, color)
            dot(shot.goalX, shot.goalY, GOAL)
            return b
        }

        /** The goal's red (OnPathColors.PointGoal). */
        private const val GOAL = 0xFFA32020.toInt()

        private fun encode(b: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            if (!b.compress(format, QUALITY, out)) error("compress refused")
            out.toByteArray()
        }
    }
}
