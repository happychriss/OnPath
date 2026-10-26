// OnPath's user texts, English + German (owner 2026-10-26), as Compose Multiplatform resources
// (docs/design/multiplatform.md: the texts never have to move again when an iPhone version comes). Compose
// resources need the Kotlin Multiplatform plugin, so they live in this small module; the app uses [Txt] / str().
// Compose Multiplatform 1.7.3 = androidx Compose 1.7 like the app (a newer one would pull a newer Compose UI).
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.compose.runtime:runtime:1.7.3")
            api("org.jetbrains.compose.components:components-resources:1.7.3")
        }
        androidMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
        }
        val androidUnitTest by getting {
            dependencies { implementation("junit:junit:4.13.2") }
        }
    }
}

android {
    namespace = "com.onpath.strings"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("user.language", "en")
            it.systemProperty("user.country", "US")
            it.systemProperty("onpath.stringsDir", file("src/commonMain/composeResources").absolutePath)
        }
    }
}

compose.resources {
    packageOfResClass = "com.onpath.strings"
    publicResClass = true
    generateResClass = always
}
