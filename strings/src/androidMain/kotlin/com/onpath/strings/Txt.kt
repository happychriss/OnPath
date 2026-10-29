package com.onpath.strings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import java.io.File
import java.util.Locale

/**
 * Every user text of the app (owner: English + German, the phone's language or the override in
 * Settings → Diagnostics → Developer tools). Texts live in `composeResources/values/strings_*.xml` (English) and
 * `values-de/` (German); `Res.string.<key>` / `Res.plurals.<key>` are generated from them.
 *
 * - In Compose: `str(Res.string.key, args…)`, `plural(Res.plurals.key, count, args…)`.
 * - Anywhere else (view model, service, pure helpers): `Txt.s(…)`, `Txt.p(…)` – plain blocking calls; after the first
 *   read of a file the texts come from Compose's cache.
 *
 * Placeholders are only `%1$s` / `%1$d` (Compose resources): numbers shown to the user are formatted first
 * (data/Format: "12,5 km" in German) and passed as text. A literal % needs no escaping ("up to %1$s %").
 *
 * JVM tests and Paparazzi (system property `onpath.stringsDir`, set by Gradle) read the XML files directly – Compose's
 * Android reader needs a running app. Either way the language is [Locale.getDefault] (AppLanguage sets it).
 */
object Txt {
    /**
     * A text that could not be read (missing key, broken file): the app sends it to the problem log and shows the key
     * instead of crashing. Unset (tests) it throws, so a missing key fails the test.
     */
    @Volatile var onError: ((String, Throwable) -> Unit)? = null

    private val files: XmlStrings? = System.getProperty("onpath.stringsDir")?.let { XmlStrings(File(it)) }

    /** The language the texts are in now: "en" or "de". */
    fun language(): String = if (Locale.getDefault().language == "de") "de" else "en"

    fun s(res: StringResource, vararg args: Any): String = guard(res.key) {
        files?.string(res.key, language(), args) ?: runBlocking { getString(res, *args) }
    }

    fun p(res: PluralStringResource, count: Int, vararg args: Any): String = guard(res.key) {
        files?.plural(res.key, language(), count, args) ?: runBlocking { getPluralString(res, count, *args) }
    }

    private inline fun guard(key: String, read: () -> String): String = try {
        read()
    } catch (e: Exception) {
        val report = onError ?: throw IllegalStateException("text '$key' could not be read", e)
        report("Text '$key' could not be read", e)
        key
    }
}

/** A text in Compose; read again when the language changes (the activity is recreated then anyway). */
@Composable
fun str(res: StringResource, vararg args: Any): String {
    val lang = Locale.getDefault().language
    return remember(res, lang, *args) { Txt.s(res, *args) }
}

/** A plural text in Compose: "1 point" / "3 points"; [args] usually start with the count. */
@Composable
fun plural(res: PluralStringResource, count: Int, vararg args: Any): String {
    val lang = Locale.getDefault().language
    return remember(res, lang, count, *args) { Txt.p(res, count, *args) }
}
