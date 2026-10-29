package com.onpath.app.data

import android.content.Context
import android.content.res.Resources
import com.onpath.app.debug.Problems
import java.util.Locale

/** Language of the app's texts: the phone's, or fixed in Developer tools (owner). Stored by [name]. */
enum class LanguageChoice(val tag: String?, val label: String) {
    SYSTEM(null, "System"), ENGLISH("en", "English"), GERMAN("de", "Deutsch");
}

/**
 * The app is English + German (owner): texts follow the phone's language (German phone → German, any other
 * → English); Settings → Diagnostics → Developer tools can fix it to English or Deutsch (kept across restarts).
 *
 * How: the choice sets the JVM default locale, which every text lookup (com.onpath.strings.Txt / str()) and number
 * format (Format: "12,5 km") reads. Applied at app start ([com.onpath.app.OnPathApp]) and again when the activity is
 * created, resumed or its configuration changes – Android may reset the default locale to the phone's on a
 * configuration change. A change in Developer tools recreates the activity.
 */
object AppLanguage {
    private const val KEY = "appLanguage"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("onpath", Context.MODE_PRIVATE)

    fun choice(ctx: Context): LanguageChoice = try {
        prefs(ctx).getString(KEY, null)?.let { LanguageChoice.valueOf(it) } ?: LanguageChoice.SYSTEM
    } catch (e: Exception) {
        Problems.warn("settings", "Setting $KEY unreadable, using the phone's language", e); LanguageChoice.SYSTEM
    }

    /** The phone's own language (the override does not change it). */
    fun phoneLocale(): Locale = try {
        Resources.getSystem().configuration.locales[0] ?: Locale.ENGLISH
    } catch (e: Exception) {
        Problems.warn("settings", "Phone language unreadable, using English", e); Locale.ENGLISH
    }

    /** The locale the texts use for [choice] with the phone set to [phone]. */
    fun localeFor(choice: LanguageChoice, phone: Locale): Locale = when (choice) {
        LanguageChoice.SYSTEM -> phone
        else -> Locale.forLanguageTag(choice.tag!!)
    }

    /** Sets the default locale from the stored choice; cheap, call it often. */
    fun apply(ctx: Context) {
        val want = localeFor(choice(ctx), phoneLocale())
        if (Locale.getDefault() != want) Locale.setDefault(want)
    }

    fun set(ctx: Context, c: LanguageChoice) {
        prefs(ctx).edit().putString(KEY, c.name).apply()
        Problems.info("settings", "App language set to ${c.label}")
        apply(ctx)
    }

    /** Diagnostics line: "English (phone language)", "Deutsch (Developer tools)". */
    fun describe(ctx: Context): String {
        val c = choice(ctx)
        val lang = if (com.onpath.strings.Txt.language() == "de") "Deutsch" else "English"
        return if (c == LanguageChoice.SYSTEM) "$lang (phone language)" else "$lang (Developer tools)"
    }
}
