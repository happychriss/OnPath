@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package btools.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.CLOCK_MONOTONIC
import platform.posix.CLOCK_REALTIME
import platform.posix.F_OK
import platform.posix.O_RDONLY
import platform.posix.SEEK_END
import platform.posix.access
import platform.posix.clock_gettime
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.lseek
import platform.posix.open
import platform.posix.pread
import platform.posix.stat
import platform.posix.stderr
import platform.posix.timespec

actual class RandomAccessFile actual constructor(path: String) {
    private val fd: Int = open(path, O_RDONLY).also { if (it < 0) throw IOException("cannot open $path") }
    private var pos = 0L
    actual fun seek(pos: Long) { this.pos = pos }
    actual fun readFully(buf: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        if (off < 0 || len < 0 || off + len > buf.size) throw IndexOutOfBoundsException("off=$off len=$len size=${buf.size}")
        var done = 0
        buf.usePinned { pinned ->
            while (done < len) {
                val n = pread(fd, pinned.addressOf(off + done), (len - done).convert(), pos + done).toInt()
                if (n <= 0) throw IOException("unexpected end of file (pos=${pos + done})")
                done += n
            }
        }
        pos += len
    }
    actual fun length(): Long = lseek(fd, 0, SEEK_END)
    actual fun close() { platform.posix.close(fd) }
}

actual fun fileExists(path: String): Boolean = access(path, F_OK) == 0
actual fun fileLength(path: String): Long = memScoped {
    val st = alloc<stat>()
    if (stat(path, st.ptr) != 0) 0L else st.st_size
}
actual fun fileLastModified(path: String): Long = memScoped {
    val st = alloc<stat>()
    if (stat(path, st.ptr) != 0) 0L else st.st_mtim.tv_sec * 1000L + st.st_mtim.tv_nsec / 1_000_000L
}
actual fun readFileBytes(path: String): ByteArray {
    val f = RandomAccessFile(path)
    try {
        val b = ByteArray(f.length().toInt())
        f.readFully(b, 0, b.size)
        return b
    } finally { f.close() }
}
private fun clock(id: Int): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(id, ts.ptr)
    ts.tv_sec * 1_000_000_000L + ts.tv_nsec
}
actual fun currentTimeMillis(): Long = clock(CLOCK_REALTIME) / 1_000_000L
actual fun nanoTime(): Long = clock(CLOCK_MONOTONIC)
actual fun printErr(line: String) { fputs(line + "\n", stderr); fflush(stderr) }
