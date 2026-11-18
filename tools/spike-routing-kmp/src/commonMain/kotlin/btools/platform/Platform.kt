// The few things BRouter takes from the JVM, as a platform edge (expect / actual). Everything else is commonMain.
package btools.platform

open class IOException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

/** Read-only random access to a file (java.io.RandomAccessFile "r" as BRouter uses it). */
expect class RandomAccessFile(path: String) {
    fun seek(pos: Long)
    fun readFully(buf: ByteArray, off: Int, len: Int)
    fun length(): Long
    fun close()
}

expect fun fileExists(path: String): Boolean
expect fun fileLength(path: String): Long
expect fun fileLastModified(path: String): Long
/** The whole file; throws IOException when it cannot be read. */
expect fun readFileBytes(path: String): ByteArray
expect fun currentTimeMillis(): Long
expect fun nanoTime(): Long
expect fun printErr(line: String)

fun readFileText(path: String): String = readFileBytes(path).decodeToString()
/** The lines of a text file without line ends (java.io.BufferedReader.readLine in a loop). */
fun readFileLines(path: String): List<String> {
    val t = readFileText(path)
    if (t.isEmpty()) return emptyList()
    val lines = t.split('\n').map { it.removeSuffix("\r") }
    return if (t.endsWith("\n")) lines.dropLast(1) else lines
}

/** java.io.File as far as BRouter needs it: a path. */
class File(val path: String) {
    constructor(parent: File, name: String) : this(if (parent.path.endsWith("/")) parent.path + name else parent.path + "/" + name)
    constructor(parent: String, name: String) : this(File(parent), name)
    fun exists(): Boolean = fileExists(path)
    fun length(): Long = fileLength(path)
    fun lastModified(): Long = fileLastModified(path)
    fun getName(): String = path.substringAfterLast('/')
    fun getAbsolutePath(): String = path
    fun getParentFile(): File? = if (path.contains('/')) File(path.substringBeforeLast('/')) else null
    override fun toString(): String = path
}

/** String.format("%.<decimals>f") for messages (HALF_UP like Java's formatter). Not for values that steer routing. */
fun formatFixed(value: Double, decimals: Int): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    var scale = 1.0
    repeat(decimals) { scale *= 10.0 }
    val neg = value < 0
    val scaled = kotlin.math.floor(kotlin.math.abs(value) * scale + 0.5).toLong()
    val s = scaled.toString().padStart(decimals + 1, '0')
    val body = if (decimals == 0) s else s.substring(0, s.length - decimals) + "." + s.substring(s.length - decimals)
    return if (neg && scaled != 0L) "-$body" else body
}

/** java.util.StringTokenizer(s, delims): the non-empty pieces between any of the delimiter chars. */
fun tokenize(s: String, delims: String = " \t\n\r"): List<String> = s.split(*delims.toCharArray()).filter { it.isNotEmpty() }
