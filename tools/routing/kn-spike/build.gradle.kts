plugins { kotlin("multiplatform") version "2.2.10" }

kotlin {
    jvm {
        binaries { executable { mainClass.set("btools.spike.MainKt") } }
    }
    linuxX64 {
        binaries {
            executable {
                entryPoint = "btools.spike.main"
                // release build = linkReleaseExecutableLinuxX64 (-opt); extra compiler options: -Pkn.opts="-Xgc=… …"
                (project.findProperty("kn.opts") as String?)?.split(' ')?.filter { it.isNotBlank() }
                    ?.let { freeCompilerArgs += it }
            }
        }
    }
}
