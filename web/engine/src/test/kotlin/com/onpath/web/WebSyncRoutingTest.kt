package com.onpath.web

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.RoutingFingerprint
import com.onpath.app.routing.SharedPlan
import com.onpath.app.sync.SyncLedger
import com.onpath.app.sync.SyncRoutingData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * Sync fields 7 / 8 on the browser's side (docs/design/web-sync.md §5.2, docs/handoff-web-sync-fingerprint.md): the
 * page's Save writes the engine's routing fingerprint (= the app's for the same profiles) and the creation day of the rd5
 * it loaded under the plan's points; a move into another folder keeps what the item had; the grey line only for a plan
 * from the phone whose comparable fingerprint differs.
 */
class WebSyncRoutingTest {
    private val app = File(System.getProperty("user.dir")).resolve("../..").canonicalFile
    private val device = "QkJCQkJCQkI"
    private val phone = "UFFSU1RVVlc"

    private fun engineWithProfiles(): String {
        val root = Files.createTempDirectory("onpath-web-fp").toFile()
        Engine.init(root.path)
        File(app, "app/src/main/assets/brouter").listFiles()!!.forEach { Engine.putFile("assets/brouter/${it.name}", it.readBytes()) }
        // E10_N45 as the browser loads it: the 200-byte index (no parts needed here) and the tail with BRouter's stamp
        val tail = ByteArray(16).also { ByteBuffer.wrap(it).putLong(DAY * 86_400_000L + 3_600_000L) }
        Engine.putLayout("E10_N45.rd5", ByteBuffer.allocate(200).apply { repeat(25) { putLong(200L) } }.array(), tail)
        return Engine.routingFingerprint()
    }

    private val plan = PlanCode.encode(SharedPlan(listOf(GeoPoint(46.683, 11.13), GeoPoint(46.686, 11.109)), Activity.HIKE, 2))

    @Test fun pageSaveWritesTheEngineFingerprintAndTheLoadedDay() {
        val fp = engineWithProfiles()
        val appFp = RoutingFingerprint.of({ n -> File(app, "app/src/main/assets/brouter/$n").readBytes() })
        assertEquals("the web's fingerprint is the app's for the same files", appFp, fp)
        val l = SyncLedger.read(WebSync.saveRouted(SyncLedger.empty().write(), ID, plan, "Algund", 1_761_900_000_000.0, device))
        val item = l.entries.getValue(ID).item
        assertEquals(fp, item.routing)
        assertEquals("E10_N45:$DAY", item.routingData)
        assertEquals(mapOf("E10_N45" to DAY), SyncRoutingData.parse(item.routingData))
    }

    @Test fun aMoveIntoAnotherFolderKeepsTheFields() {
        engineWithProfiles()
        val out = SyncLedger.read(WebSync.save(SyncLedger.empty().write(), ID, plan, "Vom Handy", 1.0, phone, "r1-0123456789ab", "E10_N45:20700"))
        val item = out.entries.getValue(ID).item
        assertEquals("r1-0123456789ab", item.routing)
        assertEquals("E10_N45:20700", item.routingData)
        val bare = SyncLedger.read(WebSync.save(SyncLedger.empty().write(), ID, plan, "Alt", 1.0, phone, "", "")).entries.getValue(ID).item
        assertNull(bare.routing); assertNull(bare.routingData)
    }

    @Test fun theGreyLineOnlyForAPhonePlanWithOtherRouting() {
        val fp = engineWithProfiles()
        fun other(routing: String, from: String) = WebSync.list(
            WebSync.save(SyncLedger.empty().write(), ID, plan, "x", 1.0, from, routing, ""), device).contains("\"otherRouting\":true")
        assertTrue("phone, other fingerprint", other("r1-0123456789ab", phone))
        assertFalse("phone, the same fingerprint", other(fp, phone))
        assertFalse("saved here", other("r1-0123456789ab", device))
        assertFalse("an older app (no field 7)", other("", phone))
        assertFalse("not comparable", other("r2-whatever", phone))
    }

    private companion object {
        const val ID = "AAAAAAAAAAAAAAAAAAAAAA"
        const val DAY = 20_752L
    }
}
