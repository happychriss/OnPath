package com.onpath.app.recording

import com.onpath.app.gpx.TrackPoint

/**
 * Track points not yet in the database (review M2: storage full or a transient SQLite error dropped them for good,
 * though they were drawn). Every accepted point gets its seq at once and waits here until it is written; the next
 * successful write flushes the older ones first, in order. Pure; [RecordingService] writes with `TourDb.appendPoint`.
 */
class PointQueue(private val max: Int = MAX) {
    data class Pending(val tourId: Long, val seq: Int, val seg: Int, val point: TrackPoint, val accuracyM: Float?)

    companion object {
        /** ~5 h at one point a second: beyond that the oldest unwritten point is given up (memory). */
        const val MAX = 18_000
    }

    private val queue = ArrayDeque<Pending>()
    /** Points given up because the queue was full (said in the problem log). */
    var dropped = 0
        private set

    val size: Int get() = queue.size

    /** Everything waiting was written another way (a rescued tour, review M7). */
    fun clear() = queue.clear()

    fun add(p: Pending) {
        if (queue.size >= max) { queue.removeFirst(); dropped++ }
        queue.addLast(p)
    }

    /**
     * Writes the waiting points in order with [write] (throws on failure); stops at the first failure and keeps that
     * point and the ones after it. Returns the exception of that failure, null when all are written.
     */
    fun flush(write: (Pending) -> Unit): Exception? {
        while (queue.isNotEmpty()) {
            try { write(queue.first()) } catch (e: Exception) { return e }
            queue.removeFirst()
        }
        return null
    }
}
