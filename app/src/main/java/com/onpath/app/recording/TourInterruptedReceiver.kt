package com.onpath.app.recording

import com.onpath.strings.*

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.onpath.app.MainActivity
import com.onpath.app.R
import com.onpath.app.data.TourDb
import com.onpath.app.debug.Problems

/**
 * A tour was still recording when the phone restarted or OnPath was updated (owner: ending a recording must never
 * happen by accident). With "Allow all the time" location the recording simply continues; otherwise
 * Android does not let it start from here, so a notification says so and opens OnPath (which offers Continue).
 * The track so far is in the database either way. Never throws.
 */
class TourInterruptedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Thread {
            try { handle(context.applicationContext, intent.action == Intent.ACTION_BOOT_COMPLETED) } catch (e: Exception) {
                Problems.error("recording", "Interrupted tour could not be checked after restart / update", e)
            } finally { pending.finish() }
        }.start()
    }

    private fun handle(app: Context, boot: Boolean) {
        if (RecordingService.state.value != null) return
        val tour = TourDb.get(app).findActiveRecording() ?: return
        // No background location (owner, before Google Play): a location service may only start from the visible app,
        // so the notification asks to open OnPath and tap Continue – no point is lost before the restart.
        RecordingService.createChannels(app)
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(app, RecordingService.CHANNEL_NAV)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Txt.s(Res.string.nav_interrupted_title))
            .setContentText(Txt.s(if (boot) Res.string.nav_interrupted_boot else Res.string.nav_interrupted_update))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                Txt.s(if (boot) Res.string.nav_interrupted_boot_long else Res.string.nav_interrupted_update_long, tour.name)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { NotificationManagerCompat.from(app).notify(INTERRUPTED_ID, n) } catch (e: SecurityException) {
            Problems.warn("recording", "Interrupted-tour notification refused (notification permission?)", e)
        }
        Problems.warn("recording", InterruptedLog.interruptedLine(tour.name, boot))
    }

    companion object { private const val INTERRUPTED_ID = 45 }
}
