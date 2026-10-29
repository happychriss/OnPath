package com.onpath.strings

import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

/** Like Compose's generated `Res`: the accessors `Res.string.<key>` / `Res.plurals.<key>` are generated (ResStrings.kt). */
object Res {
    object string
    object plurals
}

/**
 * Web stand-in for the app's `Txt` (strings/src/androidMain/.../Txt.kt): same calls, same placeholder rule as
 * XmlStrings.format (`%N$s` / `%N$d` → the N-th argument as text, nothing else touched). English for now.
 */
object Txt {
    @Volatile var onError: ((String, Throwable) -> Unit)? = null

    fun language(): String = "en"

    fun s(res: StringResource, vararg args: Any): String = format(res.text, args)

    fun p(res: PluralStringResource, count: Int, vararg args: Any): String =
        format((if (count == 1) res.one else null) ?: res.other, args)

    /** XmlStrings.format without Regex: `%` digit `$` (`s`|`d`). */
    fun format(raw: String, args: Array<out Any>): String {
        if (raw.indexOf('%') < 0) return raw
        val b = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%' && i + 3 < raw.length && raw[i + 1].isDigit() && raw[i + 2] == '$' &&
                (raw[i + 3] == 's' || raw[i + 3] == 'd')) {
                val n = raw[i + 1] - '1'
                require(n in args.indices) { "'$raw' needs argument ${n + 1}, got ${args.size}" }
                b.append(args[n].toString()); i += 4
            } else { b.append(c); i++ }
        }
        return b.toString()
    }
}
