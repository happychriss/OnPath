package com.onpath.web

import com.onpath.app.sync.PairCode
import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncItem
import com.onpath.app.sync.SyncKeys
import com.onpath.app.sync.SyncJoin
import com.onpath.app.sync.SyncLedger
import com.onpath.app.sync.SyncTrack
import com.onpath.app.sync.TrackCode
import com.onpath.app.sync.TrackMark
import com.onpath.app.sync.TrackPt

/**
 * The plan sync for the page (docs/design/web-sync.md): the app's own sync code (com.onpath.app.sync) behind small
 * string calls. Stateless: the page keeps the ledger text and the pairing code (IndexedDB) and passes them in.
 * Random bytes come from the page (crypto.getRandomValues) as Base64url text; TeaVM has no SecureRandom.
 * The exports (syncPairNew, …) sit in WebApi.kt – TeaVM exports only from the main class.
 */
object WebSync {
    private fun b64(s: String): ByteArray = SyncFormat.fromText(s) ?: ByteArray(0)

    /** A new pairing code from 32 random bytes, or "" when [random] is too short. */
    fun pairNew(random: String): String = b64(random).takeIf { it.size >= 32 }?.let { PairCode.encode(it.copyOf(32)) } ?: ""

    /** The pairing code in a link or text (normalised), or "". */
    fun pairParse(text: String): String = PairCode.parse(text)?.let { PairCode.encode(it) } ?: ""

    /** {mailbox, token, check} for a pairing code, or "null". */
    fun keys(pair: String): String {
        val k = SyncKeys.of("pair=$pair") ?: return "null"
        return "{\"mailbox\":" + str(k.mailbox) + ",\"token\":" + str(k.token) + ",\"check\":" + str(k.check) + "}"
    }

    fun newId(random: String): String = b64(random).takeIf { it.size >= SyncFormat.ID_BYTES }?.let(SyncFormat::newId) ?: ""
    fun newDevice(random: String): String = b64(random).takeIf { it.size >= SyncFormat.DEVICE_BYTES }?.let(SyncFormat::newDevice) ?: ""

    /**
     * Saved plans and imported tracks, newest first: [{id, kind, title, plan, track, updatedAt, device, mine}]
     * ([kind] "plan" / "track", [mine] = last changed on this device, [device] = who changed it last).
     */
    fun list(ledger: String, device: String): String = SyncLedger.read(ledger).let { l ->
        (l.plans() + l.tracks()).sortedWith(compareByDescending<SyncItem> { it.updatedAt }.thenBy { it.id })
    }.joinToString(",", "[", "]") {
        "{\"id\":" + str(it.id) + ",\"kind\":" + str(if (it.kind == SyncItem.TRACK) "track" else "plan") +
            ",\"title\":" + (it.title?.let(::str) ?: "null") + ",\"plan\":" + str(it.plan ?: "") + ",\"track\":" + str(it.track ?: "") +
            ",\"updatedAt\":" + it.updatedAt + ",\"device\":" + str(it.device) + ",\"mine\":" + (it.device == device) + "}"
    }

    /** The other devices: [{name, device, updatedAt}], newest first. */
    fun devices(ledger: String, device: String): String = SyncLedger.read(ledger).devices(device).joinToString(",", "[", "]") {
        "{\"name\":" + str(it.name ?: "") + ",\"device\":" + str(it.device) + ",\"updatedAt\":" + it.updatedAt + "}"
    }

    /**
     * {pending, unsaved, since, unreadable}: [pending] items wait for upload, [unsaved] of them are the user's (plans,
     * tracks, their deletes – not the own hello): what "Save" sends to the phone (A-017).
     */
    fun state(ledger: String): String = SyncLedger.read(ledger).let { l ->
        val out = l.outbox()
        "{\"pending\":" + out.size + ",\"unsaved\":" + out.count { it.item.isUserItem } + ",\"since\":" + l.since +
            ",\"unreadable\":" + l.unreadable + "}"
    }

    fun save(ledger: String, id: String, plan: String, title: String, now: Double, device: String): String =
        SyncLedger.read(ledger).also { it.save(id, plan, title.ifBlank { null }, now.toLong(), device) }.write()

    fun rename(ledger: String, id: String, title: String, now: Double, device: String): String =
        SyncLedger.read(ledger).also { it.rename(id, title.ifBlank { null }, now.toLong(), device) }.write()

    fun delete(ledger: String, id: String, now: Double, device: String): String =
        SyncLedger.read(ledger).also { it.delete(id, now.toLong(), device) }.write()

    /** Saves an imported track ([track] from [trackFit]) as a new item or a new version of [id]. */
    fun saveTrack(ledger: String, id: String, track: String, title: String, now: Double, device: String): String =
        SyncLedger.read(ledger).also { it.saveTrack(id, track, title.ifBlank { null }, now.toLong(), device) }.write()

    /**
     * A shown GPX track as the item's track text ([TrackCode.fit], thinned when long), or "". [segments] = segments
     * split by '|', points by ';', "lat,lon[,ele]"; [marks] = lines "lat lon name".
     */
    fun trackFit(segments: String, marks: String, activity: String, sac: Int): String {
        val act = com.onpath.app.routing.Activity.entries.firstOrNull { it.name == activity } ?: return ""
        val segs = segments.split('|').map { s ->
            s.split(';').mapNotNull { p ->
                val v = p.split(',')
                val la = v.getOrNull(0)?.toDoubleOrNull(); val lo = v.getOrNull(1)?.toDoubleOrNull()
                if (la == null || lo == null) null else TrackPt(la, lo, v.getOrNull(2)?.toDoubleOrNull())
            }
        }.filter { it.isNotEmpty() }
        val m = marks.split('\n').mapNotNull { l ->
            val v = l.split(' ', limit = 3)
            val la = v.getOrNull(0)?.toDoubleOrNull(); val lo = v.getOrNull(1)?.toDoubleOrNull()
            if (la == null || lo == null) null else TrackMark(la, lo, v.getOrNull(2)?.ifBlank { null })
        }
        return TrackCode.fit(SyncTrack(segs, act, sac, m)) ?: ""
    }

    /** A synced track as GPX text (the app's GpxWriter, Komoot's type) – the page opens it like an imported file. */
    fun trackGpx(track: String, title: String): String {
        val t = TrackCode.of(track) ?: return ""
        return com.onpath.app.gpx.GpxWriter.write(title.ifBlank { "Track" },
            t.segments.map { s -> s.map { com.onpath.app.gpx.TrackPoint(it.lat, it.lon, it.ele) } },
            waypoints = t.marks.map { com.onpath.app.gpx.GpxWaypoint(it.lat, it.lon, it.name) },
            type = com.onpath.app.gpx.SportType.typeOf(t.activity, t.sac))
    }

    /** The id of [device]'s hello item (to delete this browser's own hello on Unpair, A-017 point 8). */
    fun helloId(device: String): String = SyncFormat.deviceItemId(device)

    /**
     * The ledger read again from the start of the mailbox ([since] = 0, A-017 point 3). [sameMailbox] false (the
     * folder's phone has a new mailbox now): what came from the old mailbox goes – it comes back from the phone – and
     * the user's unsaved changes stay unsaved with base 0 (never uploaded by themselves: A-017 rule 8).
     */
    fun rebase(ledger: String, sameMailbox: Boolean): String {
        val l = SyncLedger.read(ledger)
        return buildString {
            append(SyncLedger.HEADER).append(' ').append(SyncLedger.VERSION).append(" 0\n")
            for (e in l.entries.values) {
                if (!sameMailbox && (!e.dirty || e.item.kind == SyncItem.DEVICE)) continue
                append(if (sameMailbox) e.rev else 0).append(' ').append(if (e.dirty) 'd' else '-').append(' ')
                    .append(SyncFormat.toText(SyncFormat.encode(e.item))).append('\n')
            }
        }
    }

    /**
     * What a mailbox page says beyond the shared decoder (§5.1): the JOIN item ({device, code} with a valid pairing code)
     * and every device hello with its role. JSON {join, hellos:[{device, name, role, deleted, updatedAt}]}.
     */
    fun peek(pair: String, page: String): String {
        val keys = SyncKeys.of("pair=$pair") ?: return "{\"join\":null,\"hellos\":[]}"
        var join = "null"
        val hellos = ArrayList<String>()
        for (line in page.split('\n')) {
            val p = line.trim().split(' ')
            if (p.size != 3) continue
            val x = SyncFormat.open(keys, p[0], b64(p[2])) ?: continue
            val secret = SyncJoin.secretOf(x)
            if (secret != null) {
                join = "{\"device\":" + str(x.device) + ",\"code\":" + str(PairCode.encode(secret)) + "}"
            } else if (x.kind == SyncItem.DEVICE) {
                hellos += "{\"device\":" + str(x.device) + ",\"name\":" + (x.name?.let(::str) ?: "null") + ",\"role\":" + (x.role ?: "null") +
                    ",\"deleted\":" + x.deleted + ",\"updatedAt\":" + x.updatedAt + "}"
            }
        }
        return "{\"join\":" + join + ",\"hellos\":" + hellos.joinToString(",", "[", "]") + "}"
    }

    /**
     * The JOIN item a phone writes into a browser's fresh mailbox ([pair] = the browser's code it scanned), sealed with
     * [nonce] (12 random bytes): "id blob". The phone app does this (A-017 app point 1); the page's tests play the phone.
     */
    fun joinBlob(pair: String, device: String, phoneCode: String, now: Double, nonce: String): String {
        val keys = SyncKeys.of("pair=$pair") ?: return ""
        val n = b64(nonce).takeIf { it.size >= 12 }?.copyOf(12) ?: return ""
        // field 5 is the bare code text, also when given a #pair= link
        val secret = PairCode.parse(phoneCode) ?: PairCode.parse("pair=" + phoneCode.trim()) ?: return ""
        return SyncJoin.ID + " " + SyncFormat.toText(SyncFormat.seal(keys, SyncJoin.item(secret, device, now.toLong()), n))
    }

    fun hello(ledger: String, device: String, name: String, now: Double): String =
        SyncLedger.read(ledger).also { it.hello(device, name, now.toLong()) }.write()

    /** This device's hello with its role ([SyncItem.ROLE_PHONE] / [SyncItem.ROLE_BROWSER], §5.1 field 6). */
    fun hello(ledger: String, device: String, name: String, now: Double, role: Int): String =
        SyncLedger.read(ledger).also { it.hello(device, name, now.toLong(), role.takeIf { r -> r > 0 }) }.write()

    fun forget(ledger: String): String = SyncLedger.read(ledger).also { it.forget() }.write()

    /**
     * One mailbox page: [page] = lines "id rev blob", [upTo] its revision. Returns the new ledger text, then a line
     * with the number of blobs that could not be opened.
     */
    fun apply(ledger: String, pair: String, page: String, upTo: Double): String {
        val keys = SyncKeys.of("pair=$pair") ?: return ledger + "\n#bad -1"
        val l = SyncLedger.read(ledger)
        val blobs = page.split('\n').mapNotNull { line ->
            val p = line.trim().split(' ')
            if (p.size != 3) return@mapNotNull null
            val rev = p[1].toLongOrNull() ?: return@mapNotNull null
            Triple(p[0], rev, b64(p[2]))
        }
        val bad = l.apply(keys, blobs, upTo.toLong())
        return l.write() + "#bad $bad"
    }

    /**
     * What to upload: lines "id baseRev blob sent" ([sent] = the item bytes, handed back to [acked]). [random] =
     * 12 random bytes per item (nonces); at most as many items as it has nonces.
     */
    fun outbox(ledger: String, pair: String, random: String): String = outbox(ledger, pair, random, "")

    /**
     * As [outbox], only the items in [only] (comma-separated ids; "" = all) – the browser sends its hello without
     * "Save", everything else only with it (A-017).
     */
    fun outbox(ledger: String, pair: String, random: String, only: String): String {
        val keys = SyncKeys.of("pair=$pair") ?: return ""
        val rnd = b64(random)
        val l = SyncLedger.read(ledger)
        val ids = only.split(',').filter { it.isNotEmpty() }.toSet()
        return l.outbox().filter { ids.isEmpty() || it.item.id in ids }.take(rnd.size / 12).mapIndexed { i, e ->
            val blob = l.seal(keys, e, rnd.copyOfRange(12 * i, 12 * i + 12))
            e.item.id + " " + e.rev + " " + SyncFormat.toText(blob) + " " + SyncFormat.toText(SyncFormat.encode(e.item))
        }.joinToString("\n")
    }

    fun acked(ledger: String, sent: String, rev: Double): String {
        val item: SyncItem = SyncFormat.fromText(sent)?.let(SyncFormat::decode) ?: return ledger
        return SyncLedger.read(ledger).also { it.acked(item, rev.toLong()) }.write()
    }

    private fun str(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
        append('"')
    }
}
