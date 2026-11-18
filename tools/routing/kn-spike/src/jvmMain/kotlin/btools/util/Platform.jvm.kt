package btools.util

import java.io.File
import java.io.RandomAccessFile

actual class RaFile actual constructor(path: String) {
  private val f = RandomAccessFile(path, "r")
  actual fun length(): Long = f.length()
  actual fun seek(pos: Long) = f.seek(pos)
  actual fun readFully(buf: ByteArray, off: Int, len: Int) = f.readFully(buf, off, len)
  actual fun close() = f.close()
}

actual fun fileExists(path: String): Boolean = File(path).exists()
actual fun readFileBytes(path: String): ByteArray = File(path).readBytes()
actual fun nanoTime(): Long = System.nanoTime()
actual fun getEnv(name: String): String? = System.getenv(name)
