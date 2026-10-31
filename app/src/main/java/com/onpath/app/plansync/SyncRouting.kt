package com.onpath.app.plansync

import com.onpath.app.debug.Problems
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.RoutingFingerprint
import com.onpath.app.sync.SyncItem
import com.onpath.app.sync.SyncRoutingData
import java.io.File

/**
 * This phone's routing as the plan sync writes it into a plan item (fields 7 and 8, docs/design/routing-fingerprint.md
 * "Sync fields") and compares a synced plan with. Calls may read files: background only.
 */
interface RoutingStamp {
    /** This phone's [RoutingFingerprint]; null = not known. */
    fun fingerprint(): String?

    /** tile ("E10_N45") → creation day ([SyncRoutingData.day]) of the rd5 file on the phone; null = a file without a readable date. */
    fun days(): Map<String, Long?>
}

/** The app's [RoutingStamp]: the profiles in assets/brouter ([read] by name) and the rd5 files in [segmentsDir]. */
class FileRoutingStamp(private val read: (String) -> ByteArray, private val segmentsDir: () -> File) : RoutingStamp {
    private val fp: String? by lazy {
        try { RoutingFingerprint.of(read) } catch (e: Exception) {
            Problems.warn("sync", "Routing fingerprint not computed – synced plans carry none", e); null
        }
    }

    override fun fingerprint(): String? = fp

    override fun days(): Map<String, Long?> = segmentsDir().listFiles { f -> f.name.endsWith(".rd5") && f.length() > 0 }.orEmpty()
        .associate { f ->
            f.name.removeSuffix(".rd5") to try {
                Rd5Parts.readIndex(f)?.let { Rd5Parts.creationTime(Rd5Parts.readTail(f, it)) }?.let(SyncRoutingData::day)
            } catch (e: java.io.IOException) {
                Problems.info("sync", "Date of routing data ${f.name} not read", e); null
            }
        }
}

/**
 * The plan sync's routing rules (owner 2026-10-31: browser and app route identically – and say so quietly when they
 * cannot). Pure.
 */
object SyncRouting {
    /** Without field 8 (an older browser / app): a tile older than this is checked for newer data when the plan opens. */
    const val FALLBACK_AGE_DAYS = 7L

    /** The rd5 tiles (no ".rd5") under [points]. */
    fun tilesOf(points: List<GeoPoint>): Set<String> = points.mapTo(sortedSetOf()) { Rd5Parts.tileName(it.lat, it.lon).removeSuffix(".rd5") }

    /** Field 8 for a plan with [points]: the phone's dates of those tiles (tiles not on the phone or without a date left out). */
    fun dataFor(points: List<GeoPoint>, days: Map<String, Long?>): String? =
        SyncRoutingData.encode(tilesOf(points).mapNotNull { t -> days[t]?.let { t to it } }.toMap())

    /** The grey line: both fingerprints are of a format this app compares, and they differ. Unknown = no line. */
    fun differs(own: String?, other: String?): Boolean =
        RoutingFingerprint.isComparable(own) && RoutingFingerprint.isComparable(other) && own != other

    /** What opening a synced plan brings: the grey line ([otherRouting]) and the tiles to check for newer data now. */
    data class Opened(val otherRouting: Boolean, val refresh: List<String>)

    /**
     * A synced plan [item] is opened with [points] on this phone (device [ownDevice], fingerprint [own], rd5 [days],
     * today = [nowMs]):
     * - the line when another device saved it with another fingerprint (a plan this phone saved itself, with an older
     *   app version, is no "planned in the browser");
     * - with field 8 the tiles whose date there is newer than the phone's ([SyncRoutingData.newer]); without it the
     *   plan's tiles on the phone older than [FALLBACK_AGE_DAYS] or without a date (instead of the 35 days of a route
     *   planned here).
     */
    fun opened(item: SyncItem, ownDevice: String?, own: String?, points: List<GeoPoint>, days: Map<String, Long?>, nowMs: Long): Opened {
        val other = item.device != ownDevice && differs(own, item.routing)
        val refresh = if (item.routingData != null) SyncRoutingData.newer(item.routingData, days)
            else tilesOf(points).filter { t -> days.containsKey(t) && (days[t]?.let { SyncRoutingData.day(nowMs) - it > FALLBACK_AGE_DAYS } ?: true) }
        return Opened(other, refresh)
    }
}

/** At most one newer-data check per tile in [gapMs] (sync triggers come every minute; a check is a HEAD request). */
class RefreshThrottle(private val gapMs: Long) {
    private val last = HashMap<String, Long>()

    /** The [tiles] due at [nowMs] – marked as checked now. */
    @Synchronized fun take(tiles: Collection<String>, nowMs: Long): List<String> =
        tiles.distinct().filter { t -> last[t]?.let { nowMs - it < gapMs } != true }.onEach { last[it] = nowMs }
}
