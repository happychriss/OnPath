import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

// Map server (docs/design/map-hosting.md): debug builds talk to the test doorman, release builds to production.
// Hostnames can be overridden with -Ponpath.mapServer.test=… / -Ponpath.mapServer.production=…; the app keys are
// never committed: -Ponpath.mapKey.test=… / -Ponpath.mapKey.production=… (or ~/.gradle/gradle.properties,
// local.properties, env ONPATH_MAP_KEY_TEST / ONPATH_MAP_KEY_PRODUCTION). Empty key = "not set" in Diagnostics.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun mapSetting(name: String, env: String, default: String): String =
    (project.findProperty(name) as String?) ?: localProps.getProperty(name) ?: System.getenv(env) ?: default
fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.onpath.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.onpath.app"
        minSdk = 26
        // Google Play: since 2026-08-31 new apps and updates must target Android 16 (API 36) – A-003 Play build.
        targetSdk = 36
        // One source of truth for the version: "-PappVersion=0.4.1" (the release workflow passes the git
        // tag) or the default below. versionCode is derived (major*10000 + minor*100 + patch), so every
        // newer version installs as an update.
        val appVersion = (project.findProperty("appVersion") as String?)?.removePrefix("v") ?: "0.17.7"
        val (vMaj, vMin, vPatch) = appVersion.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            .plus(listOf(0, 0, 0)).take(3)
        versionCode = vMaj * 10000 + vMin * 100 + vPatch
        versionName = appVersion
        buildConfigField("String", "UPDATE_REPO", "\"happychriss/OnPath\"")
        // Own updater from GitHub Releases (update/AppUpdater). Off in the Google Play build (A-003): Play forbids an
        // app that updates itself past the store.
        buildConfigField("boolean", "SELF_UPDATE", "true")
        // Pixel 8 and all current phones are arm64; keeps the APK small (MapLibre native libs).
        ndk {
            abiFilters += listOf("arm64-v8a")
            // Emulator tests (CI, .github/workflows/e2e.yml): x86_64 too, never in a released APK.
            if (project.findProperty("onpath.e2e") == "true") abiFilters += listOf("x86_64")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Fixed test signing key (in the repo on purpose): every test build can be installed as an update
    // over the previous one, no matter which machine built it. NOT for Google Play releases.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/onpath-test.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // Google Play upload key (A-003, like the owner's app "Have I been here"): only on the owner's machine, in
    // .secrets/upload-keystore.jks with its passwords in .secrets/upload-keystore.properties (storeFile – relative to
    // .secrets/ –, storePassword, keyAlias, keyPassword); ONPATH_SECRETS=<dir> points elsewhere. .secrets/ is gitignored.
    // This is the UPLOAD key: Play App Signing holds the key the app ships under. Without the file the play build is
    // unsigned – still a build, it just cannot go to Play. tools/dev/play_bundle.sh builds and checks it (docs/play-build.md).
    val secretsDir = rootProject.file(System.getenv("ONPATH_SECRETS") ?: ".secrets")
    val uploadProps = Properties().apply {
        secretsDir.resolve("upload-keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }
    fun upload(name: String) = uploadProps.getProperty(name)?.trim()?.trim('"', '\'').orEmpty()
    val uploadKeystore = secretsDir.resolve(upload("storeFile").ifEmpty { "upload-keystore.jks" })
    if (uploadKeystore.isFile) signingConfigs.create("upload") {
        storeFile = uploadKeystore
        storePassword = upload("storePassword")
        keyAlias = upload("keyAlias")
        keyPassword = upload("keyPassword")
    } else if (gradle.startParameter.taskNames.any { it.contains("Play") }) {
        logger.warn("OnPath: no Play upload key in $secretsDir (upload-keystore.properties + .jks) – the play build is UNSIGNED")
    }

    buildTypes {
        debug {
            buildConfigField("String", "MAP_SERVER_ENV", quoted("test"))
            buildConfigField("String", "MAP_SERVER_URL", quoted(mapSetting("onpath.mapServer.test",
                "ONPATH_MAP_SERVER_TEST", "https://maps-test.free-your-pictures.org")))
            buildConfigField("String", "MAP_SERVER_KEY", quoted(mapSetting("onpath.mapKey.test", "ONPATH_MAP_KEY_TEST", "")))
        }
        // The Google Play build (A-003, closed test): the app as the GitHub APK, but no own updater
        // (SELF_UPDATE = false, src/play/AndroidManifest.xml drops REQUEST_INSTALL_PACKAGES), not debuggable, signed
        // with the upload key from .secrets/. A build type, not a product flavour: every debug task the tools and workflows use
        // (assembleDebug, installDebug, testDebugUnitTest, recordPaparazziDebug, connectedDebugAndroidTest) keeps its
        // name. Map server: the TEST server like the GitHub APK – the first testers stay there (owner 2026-11-02,
        // onpath.private#5 A); production comes with the public Play release. tools/dev/play_bundle.sh → dist/onpath-<version>.aab.
        create("play") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            matchingFallbacks += listOf("release")
            // the upload key from .secrets/ (above); none → unsigned. Unminified for the first Play upload, like the
            // GitHub APK: R8 could strip what MapLibre / BRouter reach by reflection – shrinking is its own step later.
            signingConfig = signingConfigs.findByName("upload")
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("String", "MAP_SERVER_ENV", quoted("test"))
            buildConfigField("String", "MAP_SERVER_URL", quoted(mapSetting("onpath.mapServer.test",
                "ONPATH_MAP_SERVER_TEST", "https://maps-test.free-your-pictures.org")))
            buildConfigField("String", "MAP_SERVER_KEY", quoted(mapSetting("onpath.mapKey.test", "ONPATH_MAP_KEY_TEST", "")))
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "MAP_SERVER_ENV", quoted("production"))
            buildConfigField("String", "MAP_SERVER_URL", quoted(mapSetting("onpath.mapServer.production",
                "ONPATH_MAP_SERVER_PRODUCTION", "https://maps.free-your-pictures.org")))
            buildConfigField("String", "MAP_SERVER_KEY", quoted(mapSetting("onpath.mapKey.production",
                "ONPATH_MAP_KEY_PRODUCTION", "")))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
    // PMTiles in assets are read by byte range (MapLibre's asset source seeks): keep them stored, not deflated.
    androidResources { noCompress += "pmtiles" }
    // Optional world overview map (base z0-5 + pre-rendered hill shade z0-7, docs/map-pipeline.md §9b), built by
    // tools/build_overview.sh – map data, so not in git. Included when "-Ponpath.overviewAssets=DIR" (or env
    // ONPATH_OVERVIEW_ASSETS) points at a folder holding overview/*.pmtiles
    // (e.g. in ~/.gradle/gradle.properties). Without it the app works as before (map/Overview.kt).
    val overviewAssets = ((project.findProperty("onpath.overviewAssets") as String?)
        ?: System.getenv("ONPATH_OVERVIEW_ASSETS"))?.takeIf { it.isNotBlank() }?.let { file(it) }
    if (overviewAssets != null) {
        check(File(overviewAssets, "overview").isDirectory) { "onpath.overviewAssets=$overviewAssets has no overview/ folder" }
        sourceSets["main"].assets.srcDir(overviewAssets)
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        // Known findings live in the baseline: only new ones are reported (CI job "lint" in unit.yml).
        // Refresh after fixing some: delete the file, run ./gradlew :app:lintDebug once (it writes a new one).
        baseline = file("lint-baseline.xml")
        // "a newer version exists" depends on the day, not on the code: it would show up as new after every library
        // release (the baseline matches the message, which names the version)
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Every JVM test in UTC, like CI: dates in screenshots (recycle bin, My routes) otherwise depend on the
        // machine's time zone and verifyPaparazziDebug fails on another machine (owner: tests in UTC).
        unitTests.all {
            it.systemProperty("user.timezone", "UTC")
            // English like CI, whatever the machine's language: tests assert English texts and "12.5 km".
            it.systemProperty("user.language", "en")
            it.systemProperty("user.country", "US")
            // Texts in JVM tests and Paparazzi: read straight from the string files (strings/…/Txt.kt) – the Android
            // asset reader of Compose resources needs a running app.
            it.systemProperty("onpath.stringsDir", rootProject.file("strings/src/commonMain/composeResources").absolutePath)
        }
    }
}

/**
 * Settings → About → Licences (A-020): the libraries each build really bundles, from Gradle's resolved runtime
 * classpath – one "group:name:version" per line in assets/licences/deps.txt. The app pairs each with its licence
 * (data/Licences.kt); LicencesTest fails when a library has none there. No licence plugin, nothing online.
 */
abstract class LicenceDepsTask : DefaultTask() {
    @get:Input abstract val coordinates: ListProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun write() {
        val f = outputDir.get().asFile.resolve("licences/deps.txt")
        f.parentFile.mkdirs()
        f.writeText(coordinates.get().sorted().distinct().joinToString("\n", postfix = "\n"))
    }
}

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<LicenceDepsTask>("licenceDeps$cap") {
            coordinates.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map { root ->
                val out = sortedSetOf<String>()
                val seen = HashSet<ComponentIdentifier>()
                fun walk(c: org.gradle.api.artifacts.result.ResolvedComponentResult) {
                    if (!seen.add(c.id)) return
                    (c.id as? ModuleComponentIdentifier)?.let { out += "${it.group}:${it.module}:${it.version}" }
                    c.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach { walk(it.selected) }
                }
                walk(root)
                out.toList()
            })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, LicenceDepsTask::outputDir)
        // the unit test checks the debug list (a superset of release: + ui-tooling)
        if (variant.name == "debug") {
            val deps = task.flatMap { it.outputDir }.map { it.file("licences/deps.txt").asFile.absolutePath }
            tasks.withType<Test>().matching { it.name == "testDebugUnitTest" }.configureEach {
                inputs.files(task).withPropertyName("licenceDeps")
                jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Donpath.licenceDeps=${deps.get()}") })
            }
        }
    }
}

dependencies {
    // Routing engine jars (BRouter) dropped in by the routing agent.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(project(":strings"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    // the one interface androidx.core's getUnusedAppRestrictionsStatus returns (Phone setup); no Guava, no Play services
    implementation("com.google.guava:listenablefuture:1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    // Gson's streaming reader (offline search index, map cell GeoJSON, Photon answers) – declared, not only through
    // MapLibre (review Arch 13: a MapLibre upgrade dropping it would break the build); the same version MapLibre brings
    implementation("com.google.code.gson:gson:2.10.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (Android's is stubbed there).
    testImplementation("org.json:json:20240303")
    // SQLite on the JVM for the offline address search tests (Android's SQLite is not available there).
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")

    // End-to-end tests of the core features on an emulator (docs/core-features.md, app/src/androidTest)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
