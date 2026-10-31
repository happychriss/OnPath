package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * A few screens in German to see that the longer German texts fit: plan sheet, route
 * hints, activity picker, Navigate, My routes, settings. Same pictures as the English tests of the same name – the
 * test body is borrowed from there and drawn with this class's Paparazzi under a German default locale.
 */
class GermanScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )

    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    /** [t] drawing into this class's Paparazzi (its own rule is not running). */
    private fun <T : Any> borrow(t: T): T = t.also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    private val shots get() = borrow(ScreenshotTest())

    @Test fun plan() = shots.plan()
    @Test fun planWholeRoute() = shots.planWholeRoute()
    @Test fun navigateWholeRoute() = shots.navigateWholeRoute()
    @Test fun planEmpty() = shots.planEmpty()
    @Test fun planOnePoint() = shots.planOnePoint()
    @Test fun trackWithoutLine() = shots.trackWithoutLine()
    @Test fun planPointsFull() = shots.planPointsFull()
    @Test fun planFerryHint() = shots.planFerryHint()
    @Test fun routeSpotsListHints() = shots.routeSpotsListHints()
    @Test fun planMenuOpen() = shots.planMenuOpen()
    @Test fun planMenuPhoneSetup() = shots.planMenuPhoneSetup()
    @Test fun planMenuSyncPaired() = shots.planMenuSyncPaired()
    @Test fun planMenuSyncProblem() = shots.planMenuSyncProblem()
    @Test fun planMenuSyncUnpairedInBrowser() = shots.planMenuSyncUnpairedInBrowser()
    @Test fun planChipLongBikeTour() = shots.planChipLongBikeTour()
    @Test fun planChipLongHike() = shots.planChipLongHike()
    @Test fun planUnsaved() = shots.planUnsaved()
    @Test fun planSaved() = shots.planSaved()
    @Test fun planChangedNotSaved() = shots.planChangedNotSaved()
    @Test fun planUnsavedLongHike() = shots.planUnsavedLongHike()
    @Test fun planMenuUnsaved() = shots.planMenuUnsaved()
    @Test fun planPairedNote() = shots.planPairedNote()
    @Test fun planUnpairedNote() = shots.planUnpairedNote()
    @Test fun planUnpairedOfflineNote() = shots.planUnpairedOfflineNote()
    @Test fun planPairHint() = shots.planPairHint()
    @Test fun routesTourBlocked() = shots.routesTourBlocked()
    @Test fun navigate() = shots.navigate()
    @Test fun navigateNotRecording() = shots.navigateNotRecording()
    @Test fun navigateRerouteQuestion() = shots.navigateRerouteQuestion()
    @Test fun navigateFarStart() = shots.navigateFarStart()
    @Test fun navigateLeadFailed() = shots.navigateLeadFailed()
    @Test fun navigateRerouteSkipStop() = shots.navigateRerouteSkipStop()
    @Test fun navigateRerouteSkipStopNamed() = shots.navigateRerouteSkipStopNamed()
    @Test fun navigateApproximatePosition() = shots.navigateApproximatePosition()
    @Test fun navigateNotificationsOff() = shots.navigateNotificationsOff()
    @Test fun navigateNotificationsOffApproximate() = shots.navigateNotificationsOffApproximate()
    @Test fun arrival() = shots.arrival()
    @Test fun sharedPlaceMenu() = shots.sharedPlaceMenu()
    @Test fun sharedPlaceMenuEmptyPlan() = shots.sharedPlaceMenuEmptyPlan()
    @Test fun tours() = shots.tours()
    @Test fun toursDone() = shots.toursDone()
    @Test fun toursFromBrowser() = shots.toursFromBrowser()
    @Test fun toursEmptyPlanned() = shots.toursEmptyPlanned()
    @Test fun toursEmptyDone() = shots.toursEmptyDone()
    @Test fun unfinishedTour() = shots.unfinishedTour()
    @Test fun pickerHikeT2() = borrow(ActivityPickerScreenshotTest()).pickerHikeT2()
    @Test fun pickerMtb() = borrow(ActivityPickerScreenshotTest()).pickerMtb()
    @Test fun settingsList() = borrow(SettingsScreenshotTest()).list()
    @Test fun settingsListPhoneNeeded() = borrow(SettingsScreenshotTest()).listPhoneNeeded()
    @Test fun settingsListPhoneRecommended() = borrow(SettingsScreenshotTest()).listPhoneRecommended()
    @Test fun settingsPace() = borrow(SettingsScreenshotTest()).pace()
    @Test fun settingsOfflineMaps() = borrow(SettingsScreenshotTest()).offlineMaps()
    @Test fun settingsNavigation() = borrow(SettingsScreenshotTest()).navigation()
    @Test fun settingsSyncPaired() = borrow(SettingsScreenshotTest()).syncPaired()
    @Test fun settingsSyncOffline() = borrow(SettingsScreenshotTest()).syncOffline()
    @Test fun settingsSyncNotPaired() = borrow(SettingsScreenshotTest()).syncNotPaired()
    @Test fun settingsSyncUnpairedInBrowser() = borrow(SettingsScreenshotTest()).syncUnpairedInBrowser()
}
