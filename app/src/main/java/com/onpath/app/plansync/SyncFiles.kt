package com.onpath.app.plansync

import com.onpath.app.debug.Problems
import com.onpath.app.sync.PairCode
import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncLedger
import java.io.File
import java.io.FileOutputStream

/**
 * Which synced item is which tour on this phone: item id ⇄ tour uuid (the tour's stable id, data/TourDb), plus the items
 * whose remote change is not in the tour yet ([pending] – the next pass brings it in; until then the tour is not read
 * back into the ledger, so a half-done import never overwrites the other side).
 *
 * Text form: header `onpath-sync-links 1`, then per link `<item id> <tour uuid> <p|->`. Unreadable lines are dropped
 * (a lost link only means the tour is uploaded once more as a new item – nothing is lost on the phone).
 */
class SyncLinks private constructor(private val byItem: LinkedHashMap<String, String>, private val pend: MutableSet<String>) {
    val pending: Set<String> get() = pend
    val all: Map<String, String> get() = byItem

    fun tourOf(item: String): String? = byItem[item]
    fun itemOf(tour: String): String? = byItem.entries.firstOrNull { it.value == tour }?.key
    fun isPending(item: String) = item in pend

    fun link(item: String, tour: String) {
        byItem.entries.removeAll { it.value == tour && it.key != item }
        byItem[item] = tour
    }
    fun unlink(item: String) { byItem.remove(item); pend.remove(item) }
    fun markPending(item: String) { pend += item }
    fun done(item: String) { pend -= item }

    fun write(): String = buildString {
        append(HEADER).append('\n')
        for ((i, t) in byItem) append(i).append(' ').append(t).append(' ').append(if (i in pend) 'p' else '-').append('\n')
        // pending items without a tour yet (a new remote plan whose import failed)
        for (i in pend) if (i !in byItem) append(i).append(' ').append(NONE).append(" p\n")
    }

    companion object {
        const val HEADER = "onpath-sync-links 1"
        private const val NONE = "-"

        fun empty() = SyncLinks(LinkedHashMap(), LinkedHashSet())

        fun read(text: String?): SyncLinks {
            val out = empty()
            val lines = text.orEmpty().split('\n').filter { it.isNotBlank() }
            if (lines.firstOrNull() != HEADER) return out
            for (l in lines.drop(1)) {
                val p = l.split(' ')
                if (p.size != 3 || !SyncFormat.isId(p[0])) continue
                if (p[1] != NONE) out.byItem[p[0]] = p[1]
                if (p[2] == "p") out.pend += p[0]
            }
            return out
        }
    }
}

/** The pairing as stored: the code (holds the secret) and when it was made. */
data class Pairing(val code: String, val since: Long) {
    val secret: ByteArray? get() = PairCode.parse("pair=$code")
}

/**
 * The plan sync's own files in the app's private storage (`files/sync/`, docs/design/web-sync.md §11.2): the pairing,
 * this install's device id, the ledger, the links and the last good sync. Every file is written atomically (temp file,
 * flushed to disk, then renamed), so a crash leaves the old or the new file – never half of one. The folder is kept out
 * of Android's backup (res/xml/backup_rules.xml, data_extraction_rules.xml): a restored phone must not reuse an old
 * mailbox. Reading never throws.
 */
class SyncFiles(val dir: File) {
    private val pairFile = File(dir, "pair.txt")
    private val deviceFile = File(dir, "device.txt")
    private val ledgerFile = File(dir, "ledger.txt")
    private val linksFile = File(dir, "links.txt")
    private val statusFile = File(dir, "status.txt")
    private val endedFile = File(dir, "ended.txt")

    fun pairing(): Pairing? {
        val lines = read(pairFile)?.lines() ?: return null
        if (lines.firstOrNull() != PAIR_HEADER) { Problems.warn("sync", "Pairing file unreadable – not paired"); return null }
        val code = lines.firstOrNull { it.startsWith("code ") }?.removePrefix("code ")?.trim() ?: return null
        val since = lines.firstOrNull { it.startsWith("since ") }?.removePrefix("since ")?.trim()?.toLongOrNull() ?: 0
        return Pairing(code, since).takeIf { it.secret != null }
    }

    fun savePairing(secret: ByteArray, now: Long) =
        write(pairFile, "$PAIR_HEADER\ncode ${PairCode.encode(secret)}\nsince $now\n")

    fun forgetPairing() { if (pairFile.exists() && !pairFile.delete()) throw java.io.IOException("pairing file not removed") }

    /** This install's device id (8 random bytes, made once; [random] gives them). */
    fun device(random: (Int) -> ByteArray): String {
        read(deviceFile)?.trim()?.takeIf { SyncFormat.isDevice(it) }?.let { return it }
        val d = SyncFormat.newDevice(random(SyncFormat.DEVICE_BYTES))
        write(deviceFile, d + "\n")
        return d
    }

    fun ledger(): SyncLedger = SyncLedger.read(read(ledgerFile))
    fun saveLedger(l: SyncLedger) = write(ledgerFile, l.write())

    fun links(): SyncLinks = SyncLinks.read(read(linksFile))
    fun saveLinks(l: SyncLinks) = write(linksFile, l.write())

    /** When the last sync round ended well (ms), 0 = never. */
    fun lastOk(): Long = read(statusFile)?.lines()?.firstOrNull { it.startsWith("ok ") }?.removePrefix("ok ")?.trim()?.toLongOrNull() ?: 0
    fun saveLastOk(ms: Long) = write(statusFile, "ok $ms\n")

    /** When the pairing was ended in the browser (ms) and the user has not looked at Browser sync since; 0 = no. */
    fun ended(): Long = read(endedFile)?.trim()?.toLongOrNull() ?: 0
    fun saveEnded(ms: Long) = write(endedFile, "$ms\n")
    fun clearEnded() { if (endedFile.exists() && !endedFile.delete()) throw java.io.IOException("ended file not removed") }

    private fun read(f: File): String? = try {
        if (f.isFile) f.readText() else null
    } catch (e: Exception) {
        Problems.warn("sync", "Sync file ${f.name} unreadable", e); null
    }

    /** Temp file → flushed to disk → renamed over the old one. Throws on failure (disk full): the caller reports it. */
    private fun write(f: File, text: String) {
        if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("sync folder not created")
        val tmp = File(dir, f.name + TMP)
        FileOutputStream(tmp).use { out -> out.write(text.toByteArray()); out.flush(); out.fd.sync() }
        if (!tmp.renameTo(f)) {
            // some file systems refuse to rename over an existing file
            if (!f.delete() || !tmp.renameTo(f)) throw java.io.IOException("${f.name} not replaced")
        }
    }

    companion object {
        const val DIR = "sync"
        const val TMP = ".tmp"
        private const val PAIR_HEADER = "onpath-pair 1"
    }
}
