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
 * XmlStrings.format (`%N$s` / `%N$d` → the N-th argument as text, nothing else touched). The language is the page's
 * choice ([setLanguage]: "en", "de", … – one per folder of `strings/…/composeResources`); English until it is set.
 */
object Txt {
    @Volatile var onError: ((String, Throwable) -> Unit)? = null

    /** Position of the current language in [LANGUAGES] (0 = English). */
    @Volatile var index = 0
        private set

    fun language(): String = LANGUAGES[index]

    /**
     * Sets the text language; an unknown one → English. Numbers follow it as in the app (data/Format.locale: the
     * German locale for German texts). Returns the language now used.
     */
    fun setLanguage(lang: String): String {
        index = LANGUAGES.indexOf(lang).coerceAtLeast(0)
        java.util.Locale.setDefault(if (index == 0) java.util.Locale.US else java.util.Locale(language(), language().uppercase()))
        // ↑ with a country: TeaVM's number format looks up a currency, and a bare language finds none
        return language()
    }

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
