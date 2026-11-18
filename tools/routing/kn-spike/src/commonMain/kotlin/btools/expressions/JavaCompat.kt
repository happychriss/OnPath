// Helpers for the port of btools.expressions (BRouter 1.7.11-beta, MIT): the few java.lang / java.util behaviours the
// parser relies on, with the same semantics as the JDK ones.
package btools.expressions

/** java.util.StringTokenizer with a single delimiter char (delimiters are not returned, empty tokens are skipped). */
internal class JavaStringTokenizer(private val str: String, private val delim: Char) {
  private var pos = 0

  private fun skipDelimiters(from: Int): Int {
    var p = from
    while (p < str.length && str[p] == delim) p++
    return p
  }

  private fun scanToken(from: Int): Int {
    var p = from
    while (p < str.length && str[p] != delim) p++
    return p
  }

  fun hasMoreTokens(): Boolean = skipDelimiters(pos) < str.length

  fun nextToken(): String {
    pos = skipDelimiters(pos)
    if (pos >= str.length) throw NoSuchElementException()
    val start = pos
    pos = scanToken(pos)
    return str.substring(start, pos)
  }

  fun countTokens(): Int {
    var count = 0
    var p = pos
    while (true) {
      p = skipDelimiters(p)
      if (p >= str.length) break
      p = scanToken(p)
      count++
    }
    return count
  }
}

/** java.lang.Character.isWhitespace for BMP chars. */
internal fun javaIsWhitespace(c: Char): Boolean {
  val i = c.code
  if (i <= 0x20) return (i in 0x09..0x0D) || (i in 0x1C..0x20)
  if (i < 0x1680) return false
  return i == 0x1680 || (i in 0x2000..0x2006) || (i in 0x2008..0x200A) || i == 0x2028 || i == 0x2029 ||
    i == 0x205F || i == 0x3000
}

/** java.lang.String.trim(): strips chars <= ' ' on both ends. */
internal fun javaTrim(s: String): String {
  var st = 0
  var len = s.length
  while (st < len && s[st] <= ' ') st++
  while (st < len && s[len - 1] <= ' ') len--
  return if (st > 0 || len < s.length) s.substring(st, len) else s
}

/** java.lang.String.split(regex) for a single literal char: trailing empty strings are removed. */
internal fun javaSplit(s: String, delim: Char): Array<String> {
  val list = ArrayList<String>()
  var off = 0
  while (true) {
    val next = s.indexOf(delim, off)
    if (next < 0) break
    list.add(s.substring(off, next))
    off = next + 1
  }
  if (off == 0) return arrayOf(s) // no match: the string itself
  list.add(s.substring(off))
  var resultSize = list.size
  while (resultSize > 0 && list[resultSize - 1].isEmpty()) resultSize--
  return Array(resultSize) { list[it] }
}
