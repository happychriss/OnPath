// Web routing engine (spike, docs/design/web-planner.md §6): a separate Gradle build, NOT part of the app build.
// It compiles the app's Android-free Kotlin straight from app/src/main/java (no copy) + the BRouter jars, then
// TeaVM turns the bytecode into WebAssembly (and JS) for the browser.
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "onpath-web-engine"
