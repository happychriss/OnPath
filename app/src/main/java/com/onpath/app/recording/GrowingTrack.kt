package com.onpath.app.recording

import com.onpath.app.routing.GeoPoint

/**
 * The recorded track of the running tour, append-only, with cheap read-only snapshots for [RecState].
 *
 * Field 2026-11-07 ("Main thread blocked 695 ms" in the recording tick): the service copied the whole track for the app
 * after every point (`track.map { it.toList() }`, on the main thread) – O(points) per second. Here points go into
 * fixed-size chunks that are never moved or overwritten, so a snapshot is only "these chunks, this many points":
 * O(pieces), no copy, and it never changes once taken (later points land behind its size). Written on one thread (the
 * service's main thread); a snapshot may be read on any thread once it was published (the StateFlow orders it).
 */
class GrowingTrack {

    private class Piece {
        /** Chunk table; replaced (never changed in place below [chunkCount]) when it is full. */
        var chunks: Array<Array<GeoPoint?>?> = arrayOfNulls(4)
        var chunkCount = 0
        var size = 0

        fun add(p: GeoPoint) {
            val c = size ushr SHIFT
            if (c == chunkCount) {
                if (c == chunks.size) chunks = chunks.copyOf(chunks.size * 2) // old snapshots keep the old table
                chunks[c] = arrayOfNulls(CHUNK)
                chunkCount++
            }
            chunks[c]!![size and MASK] = p
            size++
        }

        fun view(): List<GeoPoint> = if (size == 0) emptyList() else View(chunks, size)
    }

    /** The first [size] points of a piece; the chunks below [size] are never written again. */
    private class View(private val chunks: Array<Array<GeoPoint?>?>, override val size: Int) : AbstractList<GeoPoint>() {
        override fun get(index: Int): GeoPoint {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("index $index, size $size")
            return chunks[index ushr SHIFT]!![index and MASK]!!
        }

        // the same piece at the same size (publish without a new point): equal without comparing every point
        override fun equals(other: Any?): Boolean =
            other === this || (other is View && other.chunks === chunks && other.size == size) || super.equals(other)

        override fun hashCode(): Int = super.hashCode()
    }

    private val pieces = ArrayList<Piece>()
    private var snapshot: List<List<GeoPoint>>? = emptyList()

    fun isEmpty(): Boolean = pieces.isEmpty()

    fun pointCount(): Int = pieces.sumOf { it.size }

    /** A new piece (after a pause, a GPS gap, a restart); points go into it from now on. */
    fun newSegment() {
        pieces += Piece()
        snapshot = null
    }

    /** One point at the end of the newest piece (a first point without a piece starts one). */
    fun add(p: GeoPoint) {
        if (pieces.isEmpty()) pieces += Piece()
        pieces.last().add(p)
        snapshot = null
    }

    /** Read-only view of the track as it is now: O(pieces), no point copied; later points never show in it. */
    fun snapshot(): List<List<GeoPoint>> =
        snapshot ?: pieces.map { it.view() }.also { snapshot = it }

    private companion object {
        const val SHIFT = 9
        const val CHUNK = 1 shl SHIFT
        const val MASK = CHUNK - 1
    }
}
