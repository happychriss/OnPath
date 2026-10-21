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
    compileSdk = 35

    defaultConfig {
        applicationId = "com.onpath.app"
        minSdk = 26
        targetSdk = 35
        // One source of truth for the version: "-PappVersion=0.4.1" (the release workflow passes the git
        // tag) or the default below. versionCode is derived (major*10000 + minor*100 + patch), so every
        // newer version installs as an update.
        val appVersion = (project.findProperty("appVersion") as String?)?.removePrefix("v") ?: "0.8.7"
        val (vMaj, vMin, vPatch) = appVersion.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            .plus(listOf(0, 0, 0)).take(3)
        versionCode = vMaj * 10000 + vMin * 100 + vPatch
        versionName = appVersion
        buildConfigField("String", "UPDATE_REPO", "\"happychriss/OnPath\"")
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

    buildTypes {
        debug {
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
    // Optional world overview map (base z0-5 + hill shade z0-6, docs/map-pipeline.md §9b), built by
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
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
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
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
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
