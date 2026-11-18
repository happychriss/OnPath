package btools.util

// The only platform-specific surface of the spike: read-only file access, a clock, the environment.

/** Read-only random access file (java.io.RandomAccessFile on the JVM, POSIX fopen/fseek/fread on native). */
expect class RaFile(path: String) {
  fun length(): Long
  fun seek(pos: Long)
  fun readFully(buf: ByteArray, off: Int, len: Int)
  fun close()
}

expect fun fileExists(path: String): Boolean
expect fun readFileBytes(path: String): ByteArray
expect fun nanoTime(): Long
expect fun getEnv(name: String): String?

/** Stand-in for java.io.IOException. */
open class IOException(msg: String) : Exception(msg)

/** Stand-in for BufferedReader.readLine() over a whole file (same line ends: \n, \r\n, \r). */
class LineReader(private val text: String) {
  private var pos = 0

  fun readLine(): String? {
    if (pos >= text.length) return null
    val start = pos
    while (pos < text.length && text[pos] != '\n' && text[pos] != '\r') pos++
    val line = text.substring(start, pos)
    if (pos < text.length) {
      if (text[pos] == '\r' && pos + 1 < text.length && text[pos + 1] == '\n') pos++
      pos++
    }
    return line
  }

  companion object {
    fun ofFile(path: String) = LineReader(readFileBytes(path).decodeToString())
  }
}
