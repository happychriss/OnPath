package com.onpath.app.setup

/**
 * What OnPath needs from the phone and whether it has it (docs/design/onboarding-phone-check.md §2, §4.1) – pure
 * rules, unit-tested; the Android reader is ui/setup/PhoneStateReader.
 */
enum class CheckId {
    /** Location access "while using the app" (L1). */
    LOCATION,
    /** Precise, not only approximate location (L2). */
    PRECISE,
    /** The phone's location switched on (L3). */
    LOCATION_ON,
    /** Notifications allowed, OnPath's channels not switched off (N1). */
    NOTIFICATIONS,
    /** The user felt the test vibration (V1). */
    VIBRATION,
    /** Battery usage not "Restricted" (B1). */
    BATTERY,
    /** A phone brand known to close apps in the background (B2). */
    BRAND,
    /** Battery saver turns GPS off with the screen (B3). */
    BATTERY_SAVER,
    /** Android removes access from apps unused for months (H1). */
    UNUSED_RESET,
}

enum class Need { REQUIRED, RECOMMENDED, OPTIONAL }

enum class LocationGrant { NONE, APPROXIMATE, PRECISE }

enum class Felt { UNKNOWN, YES, NO }

enum class ResetStatus { ON, OFF, NOT_AVAILABLE, UNKNOWN }

/** Who took something away that was there at the last use: Android's unused-app reset, or someone else. */
enum class Taken { BY_PHONE, OFF }

/** Brands with their own background killers (dontkillmyapp.com ranking); Build.MANUFACTURER lower-cased. */
enum class Brand(val names: Set<String>, val helpPage: String) {
    SAMSUNG(setOf("samsung"), "samsung"),
    XIAOMI(setOf("xiaomi", "redmi", "poco"), "xiaomi"),
    HUAWEI(setOf("huawei", "honor"), "huawei"),
    ONEPLUS(setOf("oneplus", "oppo", "realme"), "oneplus");

    companion object {
        fun of(manufacturer: String): Brand? = manufacturer.trim().lowercase().let { m -> entries.firstOrNull { m in it.names } }
    }
}

/** Battery saver location modes (PowerManager.LOCATION_MODE_*, API 28) that stop GPS while the screen is off. */
object LocationSaverMode {
    const val NO_CHANGE = 0
    const val GPS_OFF_SCREEN_OFF = 1
    const val ALL_OFF_SCREEN_OFF = 2
    const val FOREGROUND_ONLY = 3
    const val THROTTLE_SCREEN_OFF = 4

    fun stopsGps(mode: Int?): Boolean = mode == GPS_OFF_SCREEN_OFF || mode == ALL_OFF_SCREEN_OFF
}

/** The phone as OnPath sees it now (read on every return to the app). */
data class PhoneState(
    val sdk: Int,
    val manufacturer: String = "",
    val location: LocationGrant = LocationGrant.NONE,
    val locationOn: Boolean = true,
    /** Android no longer shows the location dialog (denied twice / "don't ask again"): only App info helps. */
    val locationAskBlocked: Boolean = false,
    val notificationsOn: Boolean = true,
    /** One of OnPath's notification channels is switched off. */
    val channelOff: Boolean = false,
    val notificationAskBlocked: Boolean = false,
    val hasVibrator: Boolean = true,
    val vibrationFelt: Felt = Felt.UNKNOWN,
    val backgroundRestricted: Boolean = false,
    /** UsageStatsManager standby bucket (45 = restricted); null below API 28. */
    val standbyBucket: Int? = null,
    val powerSave: Boolean = false,
    val locationSaverMode: Int? = null,
    val unusedReset: ResetStatus = ResetStatus.UNKNOWN,
    /** The user went through the brand's steps ("Done"). */
    val brandDone: Boolean = false,
) {
    val brand: Brand? get() = Brand.of(manufacturer)
}

/** What was granted when OnPath was last open, and when (Prefs) – for "your phone removed …". */
data class LastSeen(val ok: Set<CheckId>, val atMs: Long)

data class Check(val id: CheckId, val ok: Boolean, val need: Need, val taken: Taken? = null)

/** Why a guide is shown: which checks it offers. */
enum class Purpose { FIRST_SETUP, BEFORE_TOUR, LOCATE, SETTINGS }

object PhoneCheck {
    /** Android resets permissions after "a few months" unused; from this many days we say the phone did it. */
    const val RESET_DAYS = 60
    /** "Later" on a recommended step before a tour keeps it quiet this long. */
    const val SNOOZE_MS = 7L * 24 * 3600 * 1000
    const val BUCKET_RESTRICTED = 45

    fun isOk(id: CheckId, s: PhoneState): Boolean = when (id) {
        CheckId.LOCATION -> s.location != LocationGrant.NONE
        CheckId.PRECISE -> s.location == LocationGrant.PRECISE
        CheckId.LOCATION_ON -> s.locationOn
        CheckId.NOTIFICATIONS -> s.notificationsOn && !s.channelOff
        CheckId.VIBRATION -> !s.hasVibrator || s.vibrationFelt == Felt.YES
        CheckId.BATTERY -> !s.backgroundRestricted && s.standbyBucket != BUCKET_RESTRICTED
        CheckId.BRAND -> s.brand == null || s.brandDone
        CheckId.BATTERY_SAVER -> !(s.powerSave && LocationSaverMode.stopsGps(s.locationSaverMode))
        CheckId.UNUSED_RESET -> s.unusedReset != ResetStatus.ON
    }

    /** Whether a check applies to this phone and these settings at all (Settings → Phone setup shows only these). */
    fun applies(id: CheckId, s: PhoneState, vibrationOn: Boolean = true): Boolean = when (id) {
        CheckId.PRECISE -> s.location != LocationGrant.NONE
        CheckId.VIBRATION -> vibrationOn
        CheckId.BRAND -> s.brand != null
        CheckId.BATTERY_SAVER -> s.powerSave && s.sdk >= 28
        CheckId.UNUSED_RESET -> s.unusedReset == ResetStatus.ON || s.unusedReset == ResetStatus.OFF
        else -> true
    }

    fun need(id: CheckId): Need = when (id) {
        CheckId.LOCATION, CheckId.PRECISE, CheckId.LOCATION_ON -> Need.REQUIRED
        CheckId.UNUSED_RESET -> Need.OPTIONAL
        else -> Need.RECOMMENDED
    }

    /** Everything that applies, in guide order (Settings → Phone setup). */
    fun all(s: PhoneState, vibrationOn: Boolean = true, taken: Map<CheckId, Taken> = emptyMap()): List<Check> =
        CheckId.entries.filter { applies(it, s, vibrationOn) }.map { Check(it, isOk(it, s), need(it), taken[it]) }

    /**
     * The steps a guide shows, in order: only what is missing. [snoozed] = recommended items the user put off
     * ("Later") recently – left out before a tour, never in Settings.
     */
    fun steps(purpose: Purpose, s: PhoneState, vibrationOn: Boolean = true, snoozed: Set<CheckId> = emptySet()): List<CheckId> {
        val ids = when (purpose) {
            Purpose.LOCATE -> listOf(CheckId.LOCATION, CheckId.LOCATION_ON)
            Purpose.FIRST_SETUP -> listOf(CheckId.LOCATION, CheckId.PRECISE, CheckId.LOCATION_ON, CheckId.NOTIFICATIONS,
                CheckId.VIBRATION, CheckId.BATTERY, CheckId.BRAND)
            Purpose.BEFORE_TOUR -> listOf(CheckId.LOCATION, CheckId.PRECISE, CheckId.LOCATION_ON, CheckId.NOTIFICATIONS,
                CheckId.VIBRATION, CheckId.BATTERY, CheckId.BRAND, CheckId.BATTERY_SAVER)
            Purpose.SETTINGS -> CheckId.entries
        }
        return ids.filter { id ->
            val relevant = id == CheckId.PRECISE || applies(id, s, vibrationOn) // precise is asked together with location
            relevant && !isOk(id, s) && !(purpose == Purpose.BEFORE_TOUR && need(id) != Need.REQUIRED && id in snoozed)
        }
    }

    /** True when a tour can start: nothing required is missing. */
    fun tourReady(s: PhoneState): Boolean = isOk(CheckId.LOCATION, s) && isOk(CheckId.PRECISE, s) && isOk(CheckId.LOCATION_ON, s)

    /** The checks that were fine at the last use and are not now – by the phone's unused-app reset or switched off. */
    fun takenAway(prev: LastSeen?, now: PhoneState, nowMs: Long): Map<CheckId, Taken> {
        if (prev == null) return emptyMap()
        val days = (nowMs - prev.atMs) / (24L * 3600 * 1000)
        val byPhone = now.unusedReset == ResetStatus.ON && days >= RESET_DAYS
        // only permissions are reset by Android; a switched-off location or battery saver is the user's
        val resettable = setOf(CheckId.LOCATION, CheckId.PRECISE, CheckId.NOTIFICATIONS)
        return prev.ok.filter { it in setOf(CheckId.LOCATION, CheckId.PRECISE, CheckId.NOTIFICATIONS, CheckId.BATTERY) && !isOk(it, now) }
            .associateWith { if (byPhone && it in resettable) Taken.BY_PHONE else Taken.OFF }
    }

    /** What is fine now, to remember as [LastSeen] when the app goes away. */
    fun okNow(s: PhoneState): Set<CheckId> = CheckId.entries.filter { isOk(it, s) }.toSet()

    /** One line per check for the diagnostics export (testers on other phones). */
    fun describe(s: PhoneState): String = buildString {
        append("Phone check: sdk ${s.sdk}, ${s.manufacturer.ifBlank { "?" }}, location ${s.location}")
        append(if (s.locationOn) "" else " (off)")
        if (s.locationAskBlocked) append(" (no more asking)")
        append(", notifications ${if (s.notificationsOn) "on" else "off"}${if (s.channelOff) " (a channel off)" else ""}")
        append(", vibration ${if (s.hasVibrator) "felt ${s.vibrationFelt}" else "none"}")
        append(", battery ${if (s.backgroundRestricted) "restricted" else "ok"}, bucket ${s.standbyBucket ?: "?"}")
        append(", saver ${if (s.powerSave) "on (location mode ${s.locationSaverMode ?: "?"})" else "off"}")
        append(", unused reset ${s.unusedReset}")
        s.brand?.let { append(", brand ${it.name}${if (s.brandDone) " (steps done)" else ""}") }
    }
}
