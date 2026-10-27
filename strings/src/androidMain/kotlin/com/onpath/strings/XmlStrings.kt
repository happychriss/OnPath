package com.onpath.strings

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The string files read directly (JVM tests, Paparazzi, the parity test): the files in `values/` = English, in `values-de/` =
 * German. Same rules as Compose resources: text taken as written, only `\n`, `\t`, `\uXXXX` and `\\` are special, and
 * `%1$s` / `%1$d` are replaced by the arguments; a German text that is missing falls back to English.
 */
class XmlStrings(private val dir: File) {
    class Lang(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private val langs = HashMap<String, Lang>()

    fun lang(code: String): Lang = synchronized(langs) {
        langs.getOrPut(code) { read(File(dir, if (code == "en") "values" else "values-$code")) }
    }

    fun string(key: String, code: String, args: Array<out Any>): String {
        val raw = (if (code != "en") lang(code).strings[key] else null) ?: lang("en").strings[key]
            ?: error("no string '$key' in $dir")
        return format(raw, args)
    }

    fun plural(key: String, code: String, count: Int, args: Array<out Any>): String {
        val forms = (if (code != "en") lang(code).plurals[key] else null) ?: lang("en").plurals[key]
            ?: error("no plural '$key' in $dir")
        val raw = (if (count == 1) forms["one"] else null) ?: forms["other"] ?: error("plural '$key' has no 'other'")
        return format(raw, args)
    }

    companion object {
        private val placeholder = Regex("""%(\d)\$[ds]""")

        /** Like Compose resources: `%N$s` / `%N$d` → the N-th argument as text; nothing else is touched. */
        fun format(raw: String, args: Array<out Any>): String = placeholder.replace(raw) { m ->
            val i = m.groupValues[1].toInt() - 1
            require(i in args.indices) { "'$raw' needs argument ${i + 1}, got ${args.size}" }
            args[i].toString()
        }

        /** Compose resources' handling of special characters (PrepareComposeResources.handleSpecialCharacters). */
        fun unescape(s: String): String {
            val special = Regex("""\\u[a-fA-F\d]{4}|\\n|\\t""")
            val doubleSlash = Regex("""\\\\""").findAll(s).map { it.range.first }.toSet()
            return special.replace(s) { m ->
                if ((m.range.first - 1) in doubleSlash) m.value
                else when (m.value) {
                    "\\n" -> "\n"
                    "\\t" -> "\t"
                    else -> m.value.substring(2).toInt(16).toChar().toString()
                }
            }.replace("\\\\", "\\")
        }

        fun read(dir: File): Lang {
            val strings = LinkedHashMap<String, String>()
            val plurals = LinkedHashMap<String, Map<String, String>>()
            dir.listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }.forEach { f ->
                val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement
                val nodes = root.childNodes
                for (i in 0 until nodes.length) {
                    val e = nodes.item(i) as? Element ?: continue
                    val name = e.getAttribute("name")
                    when (e.tagName) {
                        "string" -> require(strings.put(name, unescape(e.textContent)) == null) { "duplicate '$name' in $dir" }
                        "plurals" -> {
                            val items = e.getElementsByTagName("item")
                            val forms = (0 until items.length).map { items.item(it) as Element }
                                .associate { it.getAttribute("quantity") to unescape(it.textContent) }
                            require(plurals.put(name, forms) == null) { "duplicate '$name' in $dir" }
                        }
                    }
                }
            }
            return Lang(strings, plurals)
        }
    }
}
