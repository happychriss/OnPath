plugins {
    kotlin("jvm") version "2.2.10"
    id("org.teavm") version "0.16.0"
}

val root = rootDir.resolve("../..")
val app = root.resolve("app/src/main/java/com/onpath/app")
val stubs = root.resolve("tools/routing/jvm-test/stubs")

// The app files the engine needs – the same list tools/routing/jvm-test/run.sh compiles. Used as they are.
val appFiles = listOf(
    "routing/RoutingContract.kt", "routing/Rd5Parts.kt", "routing/BRouterWayStats.kt", "routing/WaypointSnap.kt",
    "routing/BRouterEngine.kt", "routing/StraightLineEngine.kt", "routing/RouteTimeLimit.kt", "routing/TurnDetector.kt",
    "routing/Junctions.kt", "routing/Planner.kt", "routing/WaypointInsertion.kt", "routing/PlanEdits.kt",
    "routing/RouteSummary.kt", "navigation/JunctionCues.kt", "data/Geo.kt", "time/PaceModel.kt", "time/RouteTimes.kt",
    "routing/PlanCode.kt", "routing/PlanPoints.kt",
    // search (docs/design/web-planner.md §10): the app's index, ranking and Photon parsing
    "search/Place.kt", "search/SearchText.kt", "search/OfflineIndex.kt", "search/OnlineSearch.kt", "search/BaseMapPlaces.kt",
    "search/AddressSearch.kt", "search/AddressQuery.kt", "search/AddPreview.kt", "poi/Poi.kt", "poi/PoiKinds.kt",
    // page parity with the phone (docs/design/web-planner.md §11): undo / redo, hold and point menus, Google link-out
    "ui/PlanState.kt", "ui/MapGestures.kt", "poi/GoogleMapsLink.kt", "ui/RouteWarnings.kt",
    "gpx/GpxModel.kt", "gpx/GpxWriter.kt", "gpx/SportType.kt", "data/LastActivity.kt",
)

// The app's own unit tests for the shared files the web adds (the app's Gradle build needs an Android SDK; these run on
// the plain JVM here, so web CI checks them too).
val appTests = listOf("routing/PlanCodeTest.kt", "ui/PlanStateTest.kt", "ui/MapGesturesTest.kt", "ui/RouteWarningsTest.kt")

sourceSets {
    main {
        java.setSrcDirs(listOf("src/main/java", stubs, root.resolve("app/src/main/java/btools/router")))
        java.include("android/**", "org/**", "btools/**", "com/google/**", "OnPathHints.java")
        // our Context (src/main/java) adds startActivity for poi/GoogleMapsLink.kt; the JVM test stubs' one steps aside
        java.exclude { it.relativePath.pathString == "android/content/Context.java" && !it.file.canonicalPath.startsWith(projectDir.canonicalPath) }
    }
}
kotlin {
    jvmToolchain(21)
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
tasks.withType<JavaCompile>().configureEach { options.release = 17 }
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

dependencies {
    implementation(files(root.resolve("app/libs").listFiles { f -> f.name.endsWith(".jar") }!!.sorted()))
    implementation(teavm.libs.jsoApis)
    implementation("com.google.code.gson:gson:2.11.0") // the app gets it with MapLibre; search reads GeoJSON with it
    testImplementation("junit:junit:4.13.2")
}

teavm {
    all { mainClass = "com.onpath.web.WebApiKt" }
    js { addedToWebApp = false; moduleType = org.teavm.gradle.api.JSModuleType.ES2015 }
    wasmGC { addedToWebApp = false }
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") { source(appFiles.map { app.resolve(it) }) }
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    source(appTests.map { root.resolve("app/src/test/java/com/onpath/app").resolve(it) })
}

// Our few web replacements (src/main/java: btools.util.StackSampler, TeaVM's java.lang.Package, ReentrantLock) must
// win over the BRouter jars and TeaVM's class library: project classes first on the TeaVM classpath.
tasks.withType<org.teavm.gradle.tasks.TeaVMTask>().configureEach {
    val rest = files(classpath.from.toList()) // keeps the task dependencies (resolving to files would drop them)
    classpath.setFrom(sourceSets["main"].output, rest)
}

// JVM side of the browser comparison (web/test/compare.mjs): same Engine code, plain JVM.
val jvm by sourceSets.creating {
    compileClasspath += sourceSets["main"].output + configurations["runtimeClasspath"]
    runtimeClasspath += output + compileClasspath
}
tasks.register<JavaExec>("runJvm") {
    classpath = jvm.runtimeClasspath
    mainClass = "com.onpath.web.JvmRunKt"
    systemProperty("onpath.root", root.canonicalPath)
    args((project.findProperty("cases") as String?) ?: "../test/cases.json",
        (project.findProperty("segments") as String?) ?: "/home/user/build/routing-test/segments",
        (project.findProperty("out") as String?) ?: "build/jvm-results.json")
}
// A small rd5 with only some parts (previews / tests): -Ptile=E5_N50.rd5 -Pdir=<head/tail/part files> -Pout=<file>
tasks.register<JavaExec>("partialRd5") {
    classpath = jvm.runtimeClasspath
    mainClass = "com.onpath.web.PartialRd5Kt"
    args(project.findProperty("tile") as String? ?: "", project.findProperty("dir") as String? ?: "",
        project.findProperty("out") as String? ?: "")
}
