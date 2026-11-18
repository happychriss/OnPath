package btools.platform

actual class RandomAccessFile actual constructor(path: String) {
    private val raf = try { java.io.RandomAccessFile(path, "r") } catch (e: java.io.IOException) { throw IOException(e.message, e) }
    actual fun seek(pos: Long) = raf.seek(pos)
    actual fun readFully(buf: ByteArray, off: Int, len: Int) {
        try { raf.readFully(buf, off, len) } catch (e: java.io.IOException) { throw IOException(e.toString(), e) }
    }
    actual fun length(): Long = raf.length()
    actual fun close() = raf.close()
}

actual fun fileExists(path: String): Boolean = java.io.File(path).exists()
actual fun fileLength(path: String): Long = java.io.File(path).length()
actual fun fileLastModified(path: String): Long = java.io.File(path).lastModified()
actual fun readFileBytes(path: String): ByteArray =
    try { java.io.File(path).readBytes() } catch (e: java.io.IOException) { throw IOException(e.toString(), e) }
actual fun currentTimeMillis(): Long = System.currentTimeMillis()
actual fun nanoTime(): Long = System.nanoTime()
actual fun printErr(line: String) = System.err.println(line)
