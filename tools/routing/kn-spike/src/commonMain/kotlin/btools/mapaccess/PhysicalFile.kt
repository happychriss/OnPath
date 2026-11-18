// Port of btools.mapaccess.PhysicalFile (BRouter 1.7.11-beta, MIT)
// java.io.File -> path String, RandomAccessFile -> btools.util.RaFile. Left out: main() and checkFileIntegrity (tool).
package btools.mapaccess

import btools.codec.DataBuffers
import btools.util.ByteDataReader
import btools.util.Crc32
import btools.util.IOException
import btools.util.RaFile
import kotlin.jvm.JvmField

/**
 * cache for a single square
 */
class PhysicalFile(path: String, dataBuffers: DataBuffers, lookupVersion: Int, lookupMinorVersion: Int) {
  @JvmField
  var ra: RaFile? = null

  @JvmField
  var fileIndex: LongArray = LongArray(25)

  @JvmField
  var fileHeaderCrcs: IntArray? = null

  @JvmField
  var creationTime: Long = 0

  @JvmField
  var fileName: String? = null

  @JvmField
  var divisor: Int = 80

  @JvmField
  var elevationType: Byte = 3

  init {
    load(path, dataBuffers, lookupVersion)
  }

  private fun load(path: String, dataBuffers: DataBuffers, lookupVersion: Int) {
    fileName = getName(path)
    val iobuffer = dataBuffers.iobuffer
    val ra = RaFile(path)
    this.ra = ra
    ra.readFully(iobuffer, 0, 200)
    val fileIndexCrc = Crc32.crc(iobuffer, 0, 200)
    var dis = ByteDataReader(iobuffer)
    for (i in 0 until 25) {
      val lv = dis.readLong()
      val readVersion = (lv shr 48).toShort()
      if (i == 0 && lookupVersion != -1 && readVersion.toInt() != lookupVersion) {
        throw IOException(
          "lookup version mismatch (old rd5?) lookups.dat="
            + lookupVersion + " " + getName(path) + "=" + readVersion
        )
      }
      fileIndex[i] = lv and 0xffffffffffffL
    }

    // read some extra info from the end of the file, if present
    val len = ra.length()

    val pos = fileIndex[24]
    var extraLen = 8 + 26 * 4

    if (len == pos) return // old format o.k.

    if ((len - pos) > extraLen) {
      extraLen++
    }

    if (len < pos + extraLen) { // > is o.k. for future extensions!
      throw IOException("file of size " + len + " too short, should be " + (pos + extraLen))
    }

    ra.seek(pos)
    ra.readFully(iobuffer, 0, extraLen)
    dis = ByteDataReader(iobuffer)
    creationTime = dis.readLong()

    val crcData = dis.readInt()
    if (crcData == fileIndexCrc) {
      divisor = 80 // old format
    } else if ((crcData xor 2) == fileIndexCrc) {
      divisor = 32 // new format
    } else {
      throw IOException("top index checksum error")
    }
    val crcs = IntArray(25)
    fileHeaderCrcs = crcs
    for (i in 0 until 25) {
      crcs[i] = dis.readInt()
    }
    try {
      elevationType = dis.readByte()
    } catch (e: Exception) {
      // expected: as in Java, a missing elevation-type byte keeps the default
    }
  }

  fun close() {
    val r = ra
    if (r != null) {
      try {
        r.close()
      } catch (ee: Exception) {
        // expected: as in Java, errors on close are ignored
      }
    }
  }

  companion object {
    /** java.io.File.getName() */
    internal fun getName(path: String): String {
      return path.substring(path.lastIndexOf('/') + 1)
    }

    fun checkVersionIntegrity(path: String): Int {
      var version = -1
      var raf: RaFile? = null
      try {
        val iobuffer = ByteArray(200)
        raf = RaFile(path)
        raf.readFully(iobuffer, 0, 200)
        val dis = ByteDataReader(iobuffer)
        val lv = dis.readLong()
        version = (lv shr 48).toInt()
      } catch (e: IOException) {
        // expected: as in Java, an unreadable file gives version -1
      } finally {
        try {
          if (raf != null) raf.close()
        } catch (e: IOException) {
          throw RuntimeException(e)
        }
      }
      return version
    }
  }
}
