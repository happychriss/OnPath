package com.onpath.app.live

import kotlinx.coroutines.flow.StateFlow

/**
 * What the app (a state slice, a screen) may ask of the live sharing and read from it
 * (docs/design/live-tracking.md §2, §14.2) – the sharing's own small contract beside
 * [com.onpath.app.recording.RecordingClient]: the recording service does not know the sharing, so its contract stays
 * as it is. The app's implementation hands every call to the live lane's thread and returns at once; tests take the
 * fake of the test kit.
 */
interface LiveShareClient {
    /** What the sharing shows now; only the live lane writes it. */
    val live: StateFlow<LiveSnapshot>
    /** Share the running tour; the link to send arrives in [live] ([LiveSnapshot.link]). Nothing while no tour records. */
    fun liveStart(name: String)
    /** Stop sharing: the box on the server is deleted, the link is dead. */
    fun liveStop()
    /** The user was told that the server ended the sharing ([LiveSnapshot.endedByServer]). */
    fun liveSeen()
    /** A new name while sharing (Chriss 2026-11-28 14:11): the shared page shows it with the next upload. */
    fun liveRename(name: String) {}
}

/**
 * Live sharing's two reads of a recording (§7) – here so the app's TourDb and the JVM tests run the same text: the
 * points numbered above a cursor in order (tour, cursor, limit), and the newest point. Columns: seq, seg, lat, lon,
 * ele, time, acc. Both are served by the index `points_tour (tour_id, seq)`.
 */
object LiveSql {
    const val POINTS_AFTER = "SELECT seq, seg, lat, lon, ele, time, acc FROM points WHERE tour_id=? AND seq>? ORDER BY seq LIMIT ?"
    const val LAST_POINT = "SELECT seq, seg, lat, lon, ele, time, acc FROM points WHERE tour_id=? ORDER BY seq DESC LIMIT 1"
}
