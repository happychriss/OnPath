// Routing spike 5a (docs/design/routing-kmp-spike.md): a build of its own, NOT part of the app or planner build.
// Run: tools/dev/gradle_slot.sh -- ./gradlew -p tools/spike-routing-kmp <task>
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "spike-routing-kmp"
