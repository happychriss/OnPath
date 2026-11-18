@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package btools.util

import kotlinx.cinterop.*
import platform.posix.*

actual class RaFile actual constructor(path: String) {
  private val f: CPointer<FILE> = fopen(path, "rb") ?: throw IOException("cannot open $path")

  actual fun length(): Long {
    val cur = ftell(f)
    fseek(f, 0, SEEK_END)
    val len = ftell(f)
    fseek(f, cur, SEEK_SET)
    return len
  }

  actual fun seek(pos: Long) {
    if (fseek(f, pos, SEEK_SET) != 0) throw IOException("seek failed")
  }

  actual fun readFully(buf: ByteArray, off: Int, len: Int) {
    if (len == 0) return
    val n = buf.usePinned { fread(it.addressOf(off), 1u, len.toULong(), f) }
    if (n.toInt() != len) throw IOException("unexpected end of file")
  }

  actual fun close() {
    fclose(f)
  }
}

actual fun fileExists(path: String): Boolean = access(path, F_OK) == 0

actual fun readFileBytes(path: String): ByteArray {
  val f = RaFile(path)
  try {
    val buf = ByteArray(f.length().toInt())
    f.readFully(buf, 0, buf.size)
    return buf
  } finally {
    f.close()
  }
}

actual fun nanoTime(): Long = memScoped {
  val ts = alloc<timespec>()
  clock_gettime(CLOCK_MONOTONIC, ts.ptr)
  ts.tv_sec * 1_000_000_000L + ts.tv_nsec
}

actual fun getEnv(name: String): String? = getenv(name)?.toKString()
