package com.onpath.app.data

import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.Rd5Parts.Part
import com.onpath.app.routing.Rd5PartsTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Routing data by 1° part: ranges, resume, atomic rewrite, version change, removal (Rd5PartStore). */
class Rd5PartStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /** brouter.de stand-in: serves [file] by ranges; [breakAfter] bytes into a range the connection drops once. */
    class FakeRemote(var file: File, var etag: String) : Rd5Remote {
        var breakAfter: Long? = null
        var bytesServed = 0L
        val ranges = mutableListOf<LongRange>()
        override fun head(tile: String): Rd5Remote.Head =
            Rd5Remote.Head(file.length(), etag, 1000, file.readBytes().copyOf(Rd5Parts.INDEX_BYTES))

        override fun open(tile: String, from: Long, toExclusive: Long, etag: String?): InputStream {
            if (etag != null && etag != this.etag) throw Rd5Remote.Changed("changed")
            ranges += from until toExclusive
            val bytes = file.readBytes().copyOfRange(from.toInt(), toExclusive.toInt())
            val cut = breakAfter?.also { breakAfter = null }
            return object : InputStream() {
                var i = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (i >= bytes.size) return -1
                    if (cut != null && i >= cut) throw IOException("connection lost")
                    val n = minOf(len, bytes.size - i, if (cut != null) (cut - i).toInt() else Int.MAX_VALUE)
                    System.arraycopy(bytes, i, b, off, n); i += n; bytesServed += n
                    return n
                }
            }
        }
    }

    private val meran = Part(11, 46)       // index 6
    private val ulten = Part(10, 46)       // index 1
    private val sea = Part(10, 49)         // index 4: empty on the server

    private fun setup(): Triple<File, FakeRemote, Rd5PartStore> {
        val (remoteFile, _, _) = Rd5PartsTest.fakeRemoteFile(tmp.newFolder("server"), Rd5PartsTest.sizes())
        val remote = FakeRemote(remoteFile, "\"v1\"")
        val seg = tmp.newFolder("segments")
        return Triple(seg, remote, Rd5PartStore(seg, remote, now = { 5_000L }))
    }

    private fun partBytes(f: File, i: Int): ByteArray {
        val idx = Rd5Parts.readIndex(f)!!
        return Rd5PartsTest.slice(f, idx.start(i), idx.size(i))
    }

    @Test fun downloadsOnlyTheNeededParts() {
        val (seg, remote, store) = setup()
        val rIdx = Rd5Parts.readIndex(remote.file)!!
        assertEquals(listOf(meran), store.missing(listOf(meran)))
        assertEquals(rIdx.size(6), store.bytesToLoad(listOf(meran))) // honest size before loading
        var last = 0L to 0L
        assertEquals(listOf(meran), store.download(listOf(meran), { d, t -> last = d to t }))
        assertEquals(rIdx.size(6) to rIdx.size(6), last)
        assertTrue(store.has(meran)); assertFalse(store.has(ulten))
        val local = File(seg, "E10_N45.rd5")
        assertArrayEquals(partBytes(remote.file, 6), partBytes(local, 6))
        assertEquals(setOf(6), Rd5Parts.readIndex(local)!!.withData)
        // served: tail + part 6 only (the index comes with head)
        assertEquals(rIdx.size(6) + 113, remote.bytesServed)
        assertEquals(5_000L, local.lastModified())
        assertEquals(rIdx.size(1), store.knownSize(ulten)) // sizes of missing parts known offline
        assertTrue(store.download(listOf(meran)).isEmpty()) // nothing again
    }

    @Test fun addingPartsRewritesAndKeepsThePresentOnes() {
        val (seg, remote, store) = setup()
        store.download(listOf(meran))
        remote.bytesServed = 0
        store.download(listOf(ulten, sea))
        val local = File(seg, "E10_N45.rd5")
        assertEquals(setOf(1, 6), Rd5Parts.readIndex(local)!!.withData)
        assertArrayEquals(partBytes(remote.file, 6), partBytes(local, 6))
        assertArrayEquals(partBytes(remote.file, 1), partBytes(local, 1))
        assertTrue(store.has(sea)) // empty on the server: recorded, nothing to load
        assertEquals(Rd5Parts.readIndex(remote.file)!!.size(1), remote.bytesServed) // part 6 not fetched again (index + tail cached a few minutes)
        assertTrue(btools.mapaccess.PhysicalFile(local, btools.codec.DataBuffers(), -1, -1).let { val d = it.divisor; it.close(); d == 32 })
        assertTrue(seg.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertTrue(File(seg, ".rd5parts").listFiles()!!.isEmpty())
    }

    @Test fun brokenConnectionResumes() {
        val (seg, remote, store) = setup()
        val size = Rd5Parts.readIndex(remote.file)!!.size(6)
        store.bytesToLoad(listOf(meran)) // tail fetched now, so the break hits the part
        remote.breakAfter = 100
        try { store.download(listOf(meran)); throw AssertionError("must fail") } catch (e: IOException) { /* expected */ }
        assertFalse(store.has(meran))
        assertFalse(File(seg, "E10_N45.rd5").exists()) // never a half file in place
        assertEquals(size - 100, store.bytesToLoad(listOf(meran)))
        store.download(listOf(meran))
        assertTrue(store.has(meran))
        assertTrue(remote.ranges.last().first == Rd5Parts.readIndex(remote.file)!!.start(6) + 100) // resumed, not restarted
        assertArrayEquals(partBytes(remote.file, 6), partBytes(File(seg, "E10_N45.rd5"), 6))
    }

    @Test fun versionChangeRefetchesAllPresentParts() {
        val (seg, remote, store) = setup()
        store.download(listOf(meran))
        // the server publishes a new weekly file (other bytes, other creation time)
        val (v2, _, _) = Rd5PartsTest.fakeRemoteFile(tmp.newFolder("server2"), Rd5PartsTest.sizes(), created = 1_770_000_000_000L, seed = 99)
        remote.file = v2; remote.etag = "\"v2\""
        val later = Rd5PartStore(seg, remote, now = { 9_000L })
        later.download(listOf(ulten))
        val local = File(seg, "E10_N45.rd5")
        assertArrayEquals(partBytes(v2, 6), partBytes(local, 6)) // not mixed: meran came again from v2
        assertArrayEquals(partBytes(v2, 1), partBytes(local, 1))
        assertEquals(9_000L, local.lastModified())
        assertTrue(btools.mapaccess.PhysicalFile(local, btools.codec.DataBuffers(), -1, -1).let { val d = it.divisor; it.close(); d == 32 })
    }

    @Test fun refreshIfNewer() {
        val (seg, remote, store) = setup()
        store.download(listOf(meran, ulten))
        assertFalse(store.refreshIfNewer("E10_N45.rd5"))
        val (v2, _, _) = Rd5PartsTest.fakeRemoteFile(tmp.newFolder("server2"), Rd5PartsTest.sizes(), created = 1_770_000_000_000L, seed = 99)
        remote.file = v2; remote.etag = "\"v2\""
        remote.bytesServed = 0
        assertTrue(store.refreshIfNewer("E10_N45.rd5"))
        val idx = Rd5Parts.readIndex(v2)!!
        assertEquals(idx.size(1) + idx.size(6) + 113, remote.bytesServed) // only the parts we had
        assertArrayEquals(partBytes(v2, 6), partBytes(File(seg, "E10_N45.rd5"), 6))
        assertFalse(store.refreshIfNewer("E10_N45.rd5"))
        assertFalse(store.refreshIfNewer("E5_N50.rd5")) // not here: nothing to refresh
    }

    @Test fun serverChangesDuringDownloadStartsOver() {
        val (seg, remote, store) = setup()
        store.bytesToLoad(listOf(meran)) // head cached with v1
        val (v2, _, _) = Rd5PartsTest.fakeRemoteFile(tmp.newFolder("server2"), Rd5PartsTest.sizes(), created = 1_770_000_000_000L, seed = 99)
        remote.file = v2; remote.etag = "\"v2\""
        store.download(listOf(meran))
        assertArrayEquals(partBytes(v2, 6), partBytes(File(seg, "E10_N45.rd5"), 6))
    }

    @Test fun wholeFileCountsAsComplete() {
        val (seg, remote, store) = setup()
        remote.file.copyTo(File(seg, "E10_N45.rd5"))
        assertTrue(store.has(meran)); assertTrue(store.has(ulten))
        assertTrue(store.missing(Rd5Parts.forBox(45.0, 10.0, 50.0, 15.0)).isEmpty())
        assertTrue(store.download(listOf(meran)).isEmpty())
        assertFalse(store.refreshIfNewer("E10_N45.rd5")) // same version (same tail)
    }

    @Test fun removeDropsPartsAndFile() {
        val (seg, remote, store) = setup()
        store.download(listOf(meran, ulten))
        val local = File(seg, "E10_N45.rd5")
        val freed = store.remove(listOf(ulten))
        assertEquals(Rd5Parts.readIndex(remote.file)!!.size(1), freed)
        assertFalse(store.has(ulten)); assertTrue(store.has(meran))
        assertArrayEquals(partBytes(remote.file, 6), partBytes(local, 6))
        assertTrue(btools.mapaccess.PhysicalFile(local, btools.codec.DataBuffers(), -1, -1).let { val d = it.divisor; it.close(); d == 32 })
        // adding again needs no refetch of meran (same version)
        remote.bytesServed = 0
        store.download(listOf(ulten))
        assertEquals(Rd5Parts.readIndex(remote.file)!!.size(1), remote.bytesServed)
        store.remove(listOf(meran, ulten))
        assertFalse(local.exists()); assertFalse(File(seg, "E10_N45.parts.json").exists())
    }

    /** Against brouter.de itself (only with ONPATH_LIVE=1; reads ~300 bytes): ranges, ETag, index + tail of E10_N45. */
    @Test fun liveBrouterDeIndex() {
        org.junit.Assume.assumeTrue(System.getenv("ONPATH_LIVE") == "1")
        val store = Rd5PartStore(tmp.newFolder("live"))
        val r = store.remote("E10_N45.rd5")
        assertTrue(r.head.length > 100_000_000)
        assertTrue(r.head.etag != null && r.head.lastModified > 0)
        assertEquals(113, r.tail.size)
        r.index.check(r.head.length)
        val meranBytes = store.remoteSize(meran)
        assertTrue("$meranBytes", meranBytes in 3_000_000L..25_000_000L)
        // the tail's index checksum matches the index in the current format (^2) – what Rd5Parts.write relies on
        val newIndex = Rd5Parts.buildIndex(r.index, setOf(6))
        Rd5Parts.buildTail(r.index, r.tail, newIndex)
        println("live E10_N45: ${r.head.length} bytes, ETag ${r.head.etag}, part 11°E 46°N $meranBytes bytes")
    }

    /**
     * Downloads the real part 11°E 46°N (~10 MB) into ONPATH_LIVE_DIR – then route on it on the JVM:
     * `KTTEST=tools/routing/jvm-test/PartialTest.kt tools/routing/jvm-test/run.sh $ONPATH_LIVE_DIR`.
     */
    @Test fun livePartDownload() {
        val dir = System.getenv("ONPATH_LIVE_DIR")
        org.junit.Assume.assumeTrue(dir != null)
        val store = Rd5PartStore(File(dir!!))
        store.download(listOf(meran))
        assertTrue(store.has(meran)); assertFalse(store.has(ulten))
        assertEquals(null, btools.mapaccess.PhysicalFile.checkFileIntegrity(File(dir, "E10_N45.rd5")))
    }

    @Test fun staleListIsNotTrusted() {
        val (seg, _, store) = setup()
        store.download(listOf(meran))
        // the list says more than the file holds (crash between rd5 and list): the file's own index wins
        val side = File(seg, "E10_N45.parts.json")
        side.writeText(side.readText().replace("\"fileLength\":", "\"fileLength\":1"))
        assertTrue(store.has(meran)); assertFalse(store.has(ulten))
    }
}
