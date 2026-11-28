package com.onpath.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** „Akku sparen“ (Chriss 2026-11-28 10:38, „A und die Frage“, R-10): where the switch, the estimate and the question are wired. */
class BatterySaveWiringTest {
    private fun src(p: String) = File("src/main/java/com/onpath/app/$p").readText()
    private fun core(p: String) = File("../core/src/main/kotlin/com/onpath/app/$p").readText()

    @Test fun theSettingReachesTheRecordingAndStaysSet() {
        assertTrue(src("data/Prefs.kt").contains(".putBoolean(\"batterySave\", v.batterySave)"))
        assertTrue(src("data/Prefs.kt").contains("BatterySave.on = v.batterySave"))
        assertTrue("at app start", src("OnPathApp.kt").contains("BatterySave.on = com.onpath.app.data.Prefs(this).settings.batterySave"))
        assertTrue("the group „Akku“ first in Navigation (⚙ on the way)", src("ui/SettingsScreen.kt").substringAfter("fun NavigationSection(")
            .substringBefore("settings_group_offroute").contains("settings_battery_save"))
    }

    @Test fun gpsSlowerAndLiveSharingSlowerButOn() {
        assertTrue(src("recording/RecordingService.kt").contains("tracker.setInterval(RestWatch.normalMs())"))
        assertTrue(!src("recording/RecordingService.kt").contains("tracker.setInterval(RestWatch.NORMAL_MS)"))
        assertTrue(core("live/LiveUploader.kt").contains("< LOW_BATTERY_PCT || com.onpath.app.domain.tour.BatterySave.on"))
        val svc = src("recording/RecordingService.kt")
        assertTrue("every second near a junction / off the route", svc.contains("BatterySave.fast = com.onpath.app.domain.tour.SaveRate.fast(st.shownAlongM, st.offM,"))
        assertTrue("no route: slow throughout", svc.contains("navigator?.state ?: run { com.onpath.app.domain.tour.BatterySave.fast = false; return }"))
        assertTrue("on foot or by bike", svc.contains("BatterySave.bike = recorded.activity != Activity.HIKE"))
        assertTrue("the slow rate is no gap in the track", svc.contains("nowEl - lastGoodFixElapsed > maxOf(GAP_MS, RestWatch.normalMs() * 2 + 5_000L)"))
    }

    @Test fun theQuestionOnNavigate() {
        val n = src("ui/NavigateScreen.kt")
        assertTrue(n.contains("battery = batteryAsk(vm, rec?.tourId?.takeIf { nav != null }, toGoS)"))
        assertTrue("after the notifications question, never over an error", n.contains("else if (ui.notificationsOff) notificationsOffMessage(onNotifications, onNotificationsOk) else ui.battery?.let { batteryMessage(it, onBattery) }"))
        assertTrue(n.contains("onBattery = { answerBattery(vm, rec?.tourId, it) }"))
        assertTrue("the estimate follows a navigated tour", src("OnPathApp.kt").contains("BatteryWatch.attach(this)"))
    }
}
