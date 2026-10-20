plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

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
        val appVersion = (project.findProperty("appVersion") as String?)?.removePrefix("v") ?: "0.7.11"
        val (vMaj, vMin, vPatch) = appVersion.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            .plus(listOf(0, 0, 0)).take(3)
        versionCode = vMaj * 10000 + vMin * 100 + vPatch
        versionName = appVersion
        buildConfigField("String", "UPDATE_REPO", "\"happychriss/OnPath\"")
        // Pixel 8 and all current phones are arm64; keeps the APK small (MapLibre native libs).
        ndk { abiFilters += listOf("arm64-v8a") }
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
        release {
            isMinifyEnabled = false
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
}
