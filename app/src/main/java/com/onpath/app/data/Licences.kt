package com.onpath.app.data

import com.onpath.strings.*
import org.jetbrains.compose.resources.StringResource

/**
 * Settings → About → Licences (A-020), offline: every open-source part the APK bundles, with version and licence, and
 * the licence texts as assets. The libraries come from the build ([LIST_ASSET], written by the Gradle task
 * `licenceDeps<Variant>` from the resolved runtime classpath); [RULES] names their project and licence –
 * LicencesTest fails when a resolved library has no rule. Parts Gradle does not resolve (the BRouter jars in app/libs,
 * glyphs, icons) are [BUNDLED]. Pure, except [load] / [text].
 */
object Licences {

    /** Where the build writes the resolved libraries ("group:name:version" per line). */
    const val LIST_ASSET = "licences/deps.txt"

    /** One licence: its name and the asset with its text (null = the text is in the project's own file). */
    enum class Licence(val title: String, val textAsset: String?) {
        APACHE_2("Apache License 2.0", "licences/Apache-2.0.txt"),
        MIT("MIT License", null),
        BSD_2("BSD 2-Clause License", null),
        OFL("SIL Open Font License 1.1", "fonts/LICENSE-OFL.txt"),
        CC0("CC0 1.0", null),
    }

    /** A project on the page: name, licence, its own licence file (with its copyright lines), a note. */
    data class Project(
        val name: String,
        val licence: Licence,
        val url: String,
        /** The project's own licence file, else [Licence.textAsset]. */
        val textAsset: String? = null,
        private val noteRes: StringResource? = null,
    ) {
        val text: String? get() = textAsset ?: licence.textAsset
        /** In the app's language (strings_settings.xml). */
        val note: String? get() = noteRes?.let { Txt.s(it) }
    }

    /** A project with its libraries ("name version") in this build. */
    data class Entry(val project: Project, val libraries: List<String>)

    val ANDROIDX = Project("AndroidX · Jetpack Compose · Material (Google)", Licence.APACHE_2, "https://developer.android.com/jetpack/androidx")
    val KOTLIN = Project("Kotlin · kotlinx (JetBrains)", Licence.APACHE_2, "https://kotlinlang.org")
    val COMPOSE_MP = Project("Compose Multiplatform (JetBrains)", Licence.APACHE_2, "https://github.com/JetBrains/compose-multiplatform")
    val MAPLIBRE = Project("MapLibre Native for Android", Licence.BSD_2, "https://github.com/maplibre/maplibre-native",
        "licences/maplibre-native.txt", Res.string.settings_lic_note_maplibre)
    val MAPLIBRE_JAVA = Project("MapLibre Java (GeoJSON, Turf)", Licence.MIT, "https://github.com/maplibre/maplibre-java",
        "licences/maplibre-java.txt")
    val MAPLIBRE_GESTURES = Project("MapLibre Android Gestures", Licence.BSD_2, "https://github.com/maplibre/maplibre-gestures-android",
        "licences/maplibre-gestures.txt")
    val SQUARE = Project("OkHttp · Okio (Square)", Licence.APACHE_2, "https://square.github.io/okhttp/")
    val TIMBER = Project("Timber (Jake Wharton)", Licence.APACHE_2, "https://github.com/JakeWharton/timber")
    val GSON = Project("Gson (Google)", Licence.APACHE_2, "https://github.com/google/gson")
    val GUAVA = Project("Guava (Google)", Licence.APACHE_2, "https://github.com/google/guava")
    val JSPECIFY = Project("JSpecify", Licence.APACHE_2, "https://jspecify.dev")

    /** BRouter in app/libs (tools/routing/build_brouter_jars.sh) – not resolved by Gradle; LicencesTest checks the jars. */
    const val BROUTER_VERSION = "1.7.11-beta-onpath1"
    val BROUTER = Project("BRouter", Licence.MIT, "https://github.com/abrensch/brouter", "brouter/LICENSE-brouter.txt",
        Res.string.settings_lic_note_brouter)
    val NOTO = Project("Noto Sans", Licence.OFL, "https://github.com/notofonts/latin-greek-cyrillic", noteRes = Res.string.settings_lic_note_noto)
    val ICONS = Project("Maki (Mapbox) · Temaki (Rapid)", Licence.CC0, "https://github.com/mapbox/maki", "sprites/LICENSE-icons.txt",
        Res.string.settings_lic_note_icons)

    /** Parts that are not Gradle libraries, always listed. */
    val BUNDLED: List<Entry> = listOf(
        Entry(BROUTER, listOf("brouter-codec, -core, -expressions, -mapaccess, -util $BROUTER_VERSION")),
        Entry(NOTO, listOf("Noto Sans Regular, Medium, Italic")),
        Entry(ICONS, listOf("Maki 8.2.0, Temaki 5.13.0")),
    )

    /** Group or "group:name" → project; the first match wins (the more specific ones first). */
    val RULES: List<Pair<String, Project>> = listOf(
        "org.maplibre.gl:android-sdk-geojson" to MAPLIBRE_JAVA,
        "org.maplibre.gl:android-sdk-turf" to MAPLIBRE_JAVA,
        "org.maplibre.gl:maplibre-android-gestures" to MAPLIBRE_GESTURES,
        "org.maplibre.gl:android-sdk" to MAPLIBRE,
        "org.maplibre.gl:android-sdk-opengl" to MAPLIBRE, // the OpenGL ES build of the same library
        "org.maplibre.gl:android-sdk-vulkan" to MAPLIBRE,
        "androidx." to ANDROIDX,
        "org.jetbrains.compose." to COMPOSE_MP,
        "org.jetbrains.androidx." to COMPOSE_MP,
        "org.jetbrains.kotlin" to KOTLIN,
        "org.jetbrains.kotlinx" to KOTLIN,
        "org.jetbrains:annotations" to KOTLIN,
        "com.squareup.okhttp3" to SQUARE,
        "com.squareup.okio" to SQUARE,
        "com.jakewharton.timber" to TIMBER,
        "com.google.code.gson" to GSON,
        "com.google.guava" to GUAVA,
        "org.jspecify" to JSPECIFY,
    )

    /** The project of [coordinate] ("group:name:version"), or null when [RULES] has none. */
    fun projectOf(coordinate: String): Project? {
        val gn = coordinate.substringBeforeLast(':')
        return RULES.firstOrNull { (prefix, _) -> if (':' in prefix) gn == prefix else gn.startsWith(prefix) }?.second
    }

    /**
     * The page: MapLibre and the [BUNDLED] parts first, then the resolved libraries per project ([RULES] order).
     * A library without a rule is listed by its own name under [UNCHECKED] (LicencesTest keeps that from shipping).
     */
    fun entries(coordinates: List<String>): List<Entry> {
        val byProject = LinkedHashMap<Project, MutableList<String>>()
        for (c in coordinates.map { it.trim() }.filter { it.count { ch -> ch == ':' } == 2 }.sorted()) {
            val (group, name, version) = c.split(':')
            val p = projectOf(c) ?: UNCHECKED.copy(name = group)
            byProject.getOrPut(p) { ArrayList() } += "$name $version"
        }
        val order = RULES.map { it.second }.distinct()
        val known = order.mapNotNull { p -> byProject[p]?.let { Entry(p, it.distinct()) } }
        val unknown = byProject.filterKeys { it !in order }.map { (p, libs) -> Entry(p, libs) }
        return known.filter { it.project == MAPLIBRE } + BUNDLED + known.filter { it.project != MAPLIBRE } + unknown
    }

    /** Placeholder project of a library without a rule. */
    private val UNCHECKED = Project("?", Licence.APACHE_2, "", null, Res.string.settings_lic_unchecked)

    /** Lines of the build's list (blanks and comments dropped). */
    fun parse(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** From the APK's assets; the bundled parts alone when the list is missing (never throws). */
    fun load(assets: android.content.res.AssetManager): List<Entry> = try {
        entries(parse(assets.open(LIST_ASSET).bufferedReader().use { it.readText() }))
    } catch (e: Exception) {
        com.onpath.app.debug.Problems.warn("settings", "Licence list missing in the app – showing the bundled parts only", e)
        BUNDLED
    }

    /** A licence text from the assets, or null (never throws). */
    fun text(assets: android.content.res.AssetManager, asset: String): String? = try {
        assets.open(asset).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        com.onpath.app.debug.Problems.warn("settings", "Licence text $asset missing in the app", e)
        null
    }
}
