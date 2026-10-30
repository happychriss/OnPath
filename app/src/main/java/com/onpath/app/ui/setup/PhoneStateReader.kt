package com.onpath.app.ui.setup

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import com.onpath.app.debug.Problems
import com.onpath.app.recording.Cues
import com.onpath.app.setup.LocationGrant
import com.onpath.app.setup.PhoneState
import com.onpath.app.setup.ResetStatus
import java.util.concurrent.TimeUnit

/**
 * Reads [PhoneState] from Android (public APIs only, no Google Play services). Every part is guarded: a value that
 * cannot be read counts as fine (never a false alarm) and is noted in the problem log.
 */
object PhoneStateReader {
    /** The notification channels OnPath depends on (RecordingService.createChannels). */
    val CHANNELS = listOf(com.onpath.app.recording.TourChannels.RECORDING, com.onpath.app.recording.TourChannels.NAVIGATION, "offroute-question")

    fun location(ctx: Context): LocationGrant = when {
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) -> LocationGrant.PRECISE
        granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationGrant.APPROXIMATE
        else -> LocationGrant.NONE
    }

    fun granted(ctx: Context, perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    /**
     * [prefs] supplies what the user told us (vibration felt, brand steps done); [blocked] what the activity knows about
     * the dialogs Android will no longer show; [unusedReset] is read separately (slow, [readUnusedReset]).
     */
    fun read(ctx: Context, prefs: com.onpath.app.data.Prefs, locationAskBlocked: Boolean, notificationAskBlocked: Boolean,
             unusedReset: ResetStatus): PhoneState {
        val sdk = Build.VERSION.SDK_INT
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        return PhoneState(
            sdk = sdk,
            manufacturer = Build.MANUFACTURER ?: "",
            location = location(ctx),
            locationOn = guard(true, "location switch") {
                val lm = ctx.getSystemService(LocationManager::class.java)
                if (sdk >= 28) lm.isLocationEnabled
                else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            },
            locationAskBlocked = locationAskBlocked,
            notificationsOn = guard(true, "notifications") { androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled() },
            channelOff = guard(false, "notification channels") {
                CHANNELS.any { nm.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE }
            },
            notificationAskBlocked = notificationAskBlocked,
            hasVibrator = guard(true, "vibrator") { Cues.hasVibrator(ctx) },
            vibrationFelt = prefs.vibrationFelt,
            backgroundRestricted = guard(false, "background restriction") {
                sdk >= 28 && ctx.getSystemService(ActivityManager::class.java).isBackgroundRestricted
            },
            standbyBucket = guard(null, "standby bucket") {
                if (sdk >= 28) ctx.getSystemService(UsageStatsManager::class.java).appStandbyBucket else null
            },
            powerSave = guard(false, "battery saver") { pm.isPowerSaveMode },
            locationSaverMode = guard(null, "battery saver location mode") { if (sdk >= 28) pm.locationPowerSaveMode else null },
            unusedReset = unusedReset,
            brandDone = prefs.brandStepsDone,
        )
    }

    /** Android's unused-app reset for OnPath (androidx.core); blocking, call off the main thread. */
    fun readUnusedReset(ctx: Context): ResetStatus = try {
        when (PackageManagerCompat.getUnusedAppRestrictionsStatus(ctx).get(3, TimeUnit.SECONDS)) {
            UnusedAppRestrictionsConstants.API_30_BACKPORT, UnusedAppRestrictionsConstants.API_30,
            UnusedAppRestrictionsConstants.API_31 -> ResetStatus.ON
            UnusedAppRestrictionsConstants.DISABLED -> ResetStatus.OFF
            UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE -> ResetStatus.NOT_AVAILABLE
            else -> ResetStatus.UNKNOWN
        }
    } catch (e: Exception) {
        Problems.info("setup", "Unused-app reset state not readable", e)
        ResetStatus.UNKNOWN
    }

    private inline fun <T> guard(fallback: T, what: String, read: () -> T): T = try { read() } catch (e: Exception) {
        Problems.info("setup", "Phone check: $what not readable", e)
        fallback
    }
}
