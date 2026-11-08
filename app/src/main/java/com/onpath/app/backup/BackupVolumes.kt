package com.onpath.app.backup

/**
 * Which MediaStore volume a new backup file goes to, and which failures only mean "the phone's storage is not ready
 * yet" (JVM tested). Field bug 2026-11-08 (Samsung S10, Android 12, right after a restart): the app started before the
 * phone's shared storage was there – Android gave the process the stub path /dev/null and MediaStore answered
 * "Volume external_primary not found"; a minute later the storage was there. Such a failure is retried quietly
 * ([BackupQueue]), never shown at once.
 */
object BackupVolumes {
    /** = MediaStore.VOLUME_EXTERNAL_PRIMARY (as text: JVM tests have no Android). */
    const val PRIMARY = "external_primary"
    /** = MediaStore.VOLUME_EXTERNAL: every external volume, MediaStore picks the primary one for a new file. */
    const val EXTERNAL = "external"

    /** [names] = MediaStore.getExternalVolumeNames(): the primary volume when it is listed, else the generic "external". */
    fun choose(names: Collection<String>): String =
        if (names.any { it.equals(PRIMARY, ignoreCase = true) }) PRIMARY else EXTERNAL

    private val missing = Regex("""[Vv]olume \S+ not found""")

    /** "Volume external_primary not found" (IllegalArgumentException from MediaProvider) anywhere in [e]'s causes. */
    fun isVolumeMissing(e: Throwable?): Boolean {
        var t = e
        var depth = 0
        while (t != null && depth++ < 10) {
            if (t.message?.let { missing.containsMatchIn(it) } == true) return true
            t = t.cause
        }
        return false
    }

    /** Storage not ready yet (worth a quiet retry) – not a full disk, no missing permission. */
    fun isNotReady(e: Throwable): Boolean = (e as? BackupException)?.notReady == true || isVolumeMissing(e)
}
