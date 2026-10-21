package com.onpath.app.cells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CORE FEATURE C11 (owner 2026-10-21): map updates and "Reset maps" never touch tours and plans. The tour database
 * lives in the app's private `databases/` folder, the backup in Documents/OnPath – the map store only ever works
 * inside its data folder. A simulated phone layout: install, update and remove every map cell, then everything
 * outside the map files is unchanged.
 */
class MapsNeverTouchToursTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun mapUpdatesAndResetKeepToursAndBackups() {
        val phone = tmp.root
        val db = File(phone, "data/databases/onpath.db").apply { parentFile!!.mkdirs(); writeText("tours") }
        val wal = File(phone, "data/databases/onpath.db-wal").apply { writeText("wal") }
        val backupIndex = File(phone, "data/files/backup-index.json").apply { parentFile!!.mkdirs(); writeText("{}") }
        val backup = File(phone, "Documents/OnPath/2026-10-21 Algund.gpx").apply { parentFile!!.mkdirs(); writeText("<gpx/>") }
        val data = File(phone, "Android/data/com.onpath.app/files/onpath").apply { mkdirs() }
        val userFile = File(data, "my-notes.gpx").apply { writeText("<gpx/>") } // something the user put next to the maps

        val store = CellStore(data)
        val a = CellId(543, 361); val b = CellId(544, 361)
        fun dl(c: CellId, v: String): CellStore.Download {
            val dir = File(File(store.downloadDir, c.id), v)
            return CellStore.Download(CellFixtures.cell(dir, c, v, "$c@$v"), dir)
        }
        store.confirmLoaded(store.apply(add = listOf(dl(a, "2026-10-19"), dl(b, "2026-10-19"))).number)
        store.confirmLoaded(store.apply(add = listOf(dl(a, "2026-11-19"))).number) // map update
        store.confirmLoaded(store.remove(setOf(a, b)).number) // Reset maps: every area deleted
        assertTrue(store.installed().isEmpty())

        for ((f, text) in listOf(db to "tours", wal to "wal", backupIndex to "{}", backup to "<gpx/>", userFile to "<gpx/>")) {
            assertTrue("${f.path} must survive map changes", f.isFile)
            assertEquals(text, f.readText())
        }
    }
}
