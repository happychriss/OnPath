package org.jetbrains.compose.resources

/**
 * Web stand-in for Compose Multiplatform's resource handles (the app's texts, docs/i18n.md). The generated
 * accessors (web/engine/build.gradle.kts, generateStrings) carry the English text; equal when the key is equal.
 */
class StringResource(val key: String, val text: String) {
    override fun equals(other: Any?) = other is StringResource && other.key == key
    override fun hashCode() = key.hashCode()
    override fun toString() = "StringResource($key)"
}

class PluralStringResource(val key: String, val one: String?, val other: String) {
    override fun equals(other: Any?) = other is PluralStringResource && other.key == key
    override fun hashCode() = key.hashCode()
    override fun toString() = "PluralStringResource($key)"
}
