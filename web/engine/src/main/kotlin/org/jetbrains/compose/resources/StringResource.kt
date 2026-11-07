package org.jetbrains.compose.resources

import com.onpath.strings.Txt

/**
 * Web stand-in for Compose Multiplatform's resource handles (the app's texts, docs/i18n.md). The generated accessors
 * (web/engine/build.gradle.kts, generateStrings) carry the text in every language ([com.onpath.strings.LANGUAGES]
 * order, null = not translated → English); [text] is the one of the language set now ([Txt.language]). Equal when
 * the key is equal.
 */
class StringResource(val key: String, val texts: Array<String?>) {
    val text: String get() = texts.getOrNull(Txt.index) ?: texts[0]!!
    override fun equals(other: Any?) = other is StringResource && other.key == key
    override fun hashCode() = key.hashCode()
    override fun toString() = "StringResource($key)"
}

class PluralStringResource(val key: String, val ones: Array<String?>, val others: Array<String?>) {
    private val i: Int get() = if (others.getOrNull(Txt.index) != null) Txt.index else 0
    /** The `one` form, or null when the language has none (then `other` is used, as Compose does). */
    val one: String? get() = ones.getOrNull(i)
    val other: String get() = others[i]!!
    override fun equals(other: Any?) = other is PluralStringResource && other.key == key
    override fun hashCode() = key.hashCode()
    override fun toString() = "PluralStringResource($key)"
}
