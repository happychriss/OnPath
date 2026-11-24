// :state – the screens' state and the user's actions as plain Kotlin on the JVM (docs/design/umbau-phase-2.md): snapshot
// state (Compose RUNTIME only – no Compose UI, no compiler plugin: a @Composable here does not compile) plus the slices
// that hold it. Depends on :core, never the other way (module order :app → :state → :core). Package names are the app's
// (com.onpath.app.ui…): a package may live in :state and in :app. Tests run here without the Android build.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("ru.vyarus.animalsniffer")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// test helpers shared with :app's and :core's tests (Sources.kt, JdbcTours ...): the same folder is a test source dir of all three
sourceSets.test { kotlin.srcDir("../core/src/testkit/kotlin") }

dependencies {
    // Android API 26 (minSdk), as in :core: a JDK-only call would compile here and crash on Android 8-12
    signature("com.toasttab.android:gummy-bears-api-26:0.12.0@signature")
    // everything the slices work on: rules, data types, the texts (:strings), coroutines, gson
    api(project(":core"))
    // the snapshot state (mutableStateOf …): the runtime artifact only. :core gets it through :strings already; named here
    // so :state does not depend on that accident. Gradle picks the JVM variant here and the Android one for :app.
    api("org.jetbrains.compose.runtime:runtime:1.7.3")
    // Android has org.json built in: compile against the jar, never ship it
    compileOnly("org.json:json:20240303")
    // BRouter, as in :core: the app's own jars
    compileOnly(fileTree(mapOf("dir" to "../app/libs", "include" to listOf("*.jar"))))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    testImplementation(fileTree(mapOf("dir" to "../app/libs", "include" to listOf("*.jar"))))
}

animalsniffer {
    sourceSets = listOf(project.sourceSets.main.get())  // main only: tests run on the JVM
    // as :core: org.json is built into Android, the jar is compile-only and must not count as "known" classes
    excludeJars("json-*")
}
tasks.test { dependsOn("animalsnifferMain")
    // WebReadyRuleTest reads these: a change of the list or of the engine's stand-ins / excludes must run it again (Gradle would skip it)
    inputs.files("web-ready.txt", "../web/engine/core-excludes.txt")
    inputs.dir("../web/engine/src/main/kotlin")

    // like :core and :app: UTC, English, the texts straight from the XML files
    systemProperty("user.timezone", "UTC")
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    systemProperty("onpath.stringsDir", rootProject.file("strings/src/commonMain/composeResources").absolutePath)
}

// The name CI knows: `./gradlew testDebugUnitTest` (unit.yml) picks this up beside the app's and :core's own.
// :app:testDebugUnitTest depends on :state:test (app/build.gradle.kts) unless -Ponpath.coreTests=explicit (the gate names it).
tasks.register("testDebugUnitTest") { dependsOn(tasks.test) }
