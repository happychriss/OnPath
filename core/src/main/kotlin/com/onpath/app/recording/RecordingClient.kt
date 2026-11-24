package com.onpath.app.recording

import kotlinx.coroutines.flow.StateFlow

/**
 * The wire strings of the recording service's Intents: the actions and the extras' names (written out as literals, a
 * notification button of an older build and a sticky restart keep meaning the same – RecordingWireTest pins every value).
 * RecordingService keeps its constant names as aliases of these.
 */
object RecordingWire {
    const val ACTION_START = "com.onpath.app.START"
    const val ACTION_PAUSE = "com.onpath.app.PAUSE"
    const val ACTION_RESUME = "com.onpath.app.RESUME"
    const val ACTION_STOP = "com.onpath.app.STOP"
    const val ACTION_FOLLOW_CHANGED = "com.onpath.app.FOLLOW_CHANGED"
    const val ACTION_NAVIGATING = "com.onpath.app.NAVIGATING"
    const val EXTRA_ON = "on"
    const val ACTION_SETTINGS = "com.onpath.app.SETTINGS"
    const val ACTION_TAKE_ROUTE = "com.onpath.app.TAKE_ROUTE"
    const val ACTION_KEEP_ROUTE = "com.onpath.app.KEEP_ROUTE"
    const val ACTION_TAKE_VIA = "com.onpath.app.TAKE_VIA"
    const val ACTION_LEAD_THERE = "com.onpath.app.LEAD_THERE"
    const val ACTION_JUST_RECORD = "com.onpath.app.JUST_RECORD"
    const val ACTION_CANCEL_LEAD = "com.onpath.app.CANCEL_LEAD"
    const val ACTION_LEAD_FAILED_OK = "com.onpath.app.LEAD_FAILED_OK"
    const val ACTION_JOIN_HERE = "com.onpath.app.JOIN_HERE"
    const val EXTRA_QUESTION = "question"
    const val ACTION_PROPOSE_SWITCH = "com.onpath.app.PROPOSE_SWITCH"
    const val EXTRA_TIME_S = "timeS"
    const val EXTRA_CHECK_NOW = "checkNow"
    const val ACTION_SAY = "com.onpath.app.SAY"
    const val EXTRA_SAY = "say"
    const val SAY_ARRIVED = "arrived"
    const val SAY_NO_DESTINATION = "noDestination"
    const val ACTION_CHECK_NOW = "com.onpath.app.CHECK_NOW"
    const val EXTRA_CONTINUE_ID = "continueId"
    const val EXTRA_ACTIVITY = "activity"
    const val EXTRA_SAC = "sac"
    /** The activity of the running tour changed in the app (free recording): [EXTRA_ACTIVITY] + [EXTRA_SAC], as with START. */
    const val ACTION_ACTIVITY_CHANGED = "com.onpath.app.ACTIVITY_CHANGED"
}

/**
 * One message to the recording service: the Intent's action and its extras, Android-free – so what the app sends is a
 * value a JVM test can compare (RecordingClientTest pins every action and extra). The notification's buttons are the
 * same commands (the recording service builds its PendingIntents from them).
 */
data class RecordingCommand(val action: String, val extras: Map<String, Any> = emptyMap()) {

    companion object {
        fun start(activity: String, sac: Int) = RecordingCommand(RecordingWire.ACTION_START,
            mapOf(RecordingWire.EXTRA_ACTIVITY to activity, RecordingWire.EXTRA_SAC to sac))
        fun continueTour(tourId: Long) = RecordingCommand(RecordingWire.ACTION_START, mapOf(RecordingWire.EXTRA_CONTINUE_ID to tourId))
        val pause = RecordingCommand(RecordingWire.ACTION_PAUSE)
        val resume = RecordingCommand(RecordingWire.ACTION_RESUME)
        val stop = RecordingCommand(RecordingWire.ACTION_STOP)
        /** [checkNow] null = no extra (the service reads "false"). */
        fun followChanged(checkNow: Boolean? = null) = RecordingCommand(RecordingWire.ACTION_FOLLOW_CHANGED,
            if (checkNow == null) emptyMap() else mapOf(RecordingWire.EXTRA_CHECK_NOW to checkNow))
        fun navigating(on: Boolean) = RecordingCommand(RecordingWire.ACTION_NAVIGATING, mapOf(RecordingWire.EXTRA_ON to on))
        fun activityChanged(activity: String, sac: Int) = RecordingCommand(RecordingWire.ACTION_ACTIVITY_CHANGED,
            mapOf(RecordingWire.EXTRA_ACTIVITY to activity, RecordingWire.EXTRA_SAC to sac))
        val settingsChanged = RecordingCommand(RecordingWire.ACTION_SETTINGS)
        val checkNow = RecordingCommand(RecordingWire.ACTION_CHECK_NOW)
        fun say(what: String) = RecordingCommand(RecordingWire.ACTION_SAY, mapOf(RecordingWire.EXTRA_SAY to what))
        fun proposeSwitch(timeS: Double) = RecordingCommand(RecordingWire.ACTION_PROPOSE_SWITCH, mapOf(RecordingWire.EXTRA_TIME_S to timeS))
        // answers to the question [questionId]
        fun takeRoute(questionId: Int) = answer(RecordingWire.ACTION_TAKE_ROUTE, questionId)
        fun takeVia(questionId: Int) = answer(RecordingWire.ACTION_TAKE_VIA, questionId)
        fun keepRoute(questionId: Int) = answer(RecordingWire.ACTION_KEEP_ROUTE, questionId)
        fun leadThere(questionId: Int) = answer(RecordingWire.ACTION_LEAD_THERE, questionId)
        fun justRecord(questionId: Int) = answer(RecordingWire.ACTION_JUST_RECORD, questionId)
        fun cancelLead(questionId: Int) = answer(RecordingWire.ACTION_CANCEL_LEAD, questionId)
        fun joinHere(questionId: Int) = answer(RecordingWire.ACTION_JOIN_HERE, questionId)
        fun leadFailedOk(questionId: Int) = answer(RecordingWire.ACTION_LEAD_FAILED_OK, questionId)
        private fun answer(action: String, questionId: Int) = RecordingCommand(action, mapOf(RecordingWire.EXTRA_QUESTION to questionId))
    }
}

/**
 * The contract between the app (view model, state holders) and the recording service: what the app may ask of the
 * service – one function per Intent action – and what it reads from it. The service is the only one that writes
 * [state], [finished] and [startFailed]; the app says "seen", it never clears the value itself.
 * [ServiceRecordingClient] is the app's; tests take a fake (testkit).
 *
 * A command does not start a service that is not recording (except [start] / [continueTour]): the service stops
 * itself again. Callers that sent only during a tour still ask [state] first.
 */
interface RecordingClient {
    /** The running tour (null = none). */
    val state: StateFlow<RecState?>
    /** Id of the last tour stopped; the app shows its summary and says [finishedSeen]. */
    val finished: StateFlow<Long?>
    /** Why the service stopped by itself (plain text); the app shows it and says [startFailedSeen]. */
    val startFailed: StateFlow<String?>
    fun finishedSeen()
    fun startFailedSeen()
    /** Tours split off the last stopped tour ("Split into two tours"), handed out once. */
    fun takeFinishedParts(): List<Long>

    fun send(command: RecordingCommand)

    fun start(activity: String, sac: Int) = send(RecordingCommand.start(activity, sac))
    fun continueTour(tourId: Long) = send(RecordingCommand.continueTour(tourId))
    fun pause(pause: Boolean) = send(if (pause) RecordingCommand.pause else RecordingCommand.resume)
    fun stop() = send(RecordingCommand.stop)
    /** What is followed changed on disk (FollowStore): read it again. [checkNow]: check the next fix at once. */
    fun followChanged(checkNow: Boolean? = null) = send(RecordingCommand.followChanged(checkNow))
    /** The app is in Navigate ([on]) or in Plan. */
    fun navigating(on: Boolean) = send(RecordingCommand.navigating(on))
    /** The tour's activity was switched in the app (check sheet, free recording): the service records / times it as [activity]. */
    fun activityChanged(activity: String, sac: Int) = send(RecordingCommand.activityChanged(activity, sac))
    fun settingsChanged() = send(RecordingCommand.settingsChanged)
    /** "Weiter geht's" back on the map: check the next fix at once. */
    fun checkNow() = send(RecordingCommand.checkNow)
    /** Speak an app event ([RecordingWire.SAY_ARRIVED] / [RecordingWire.SAY_NO_DESTINATION]). */
    fun say(what: String) = send(RecordingCommand.say(what))
    /** Activity check: a proposed session waits in the hand-over file (FollowStore.propose); [timeS] = its time. */
    fun proposeSwitch(timeS: Double) = send(RecordingCommand.proposeSwitch(timeS))
    fun takeRoute(questionId: Int) = send(RecordingCommand.takeRoute(questionId))
    fun takeVia(questionId: Int) = send(RecordingCommand.takeVia(questionId))
    fun keepRoute(questionId: Int) = send(RecordingCommand.keepRoute(questionId))
    fun leadThere(questionId: Int) = send(RecordingCommand.leadThere(questionId))
    fun justRecord(questionId: Int) = send(RecordingCommand.justRecord(questionId))
    fun cancelLead(questionId: Int) = send(RecordingCommand.cancelLead(questionId))
    fun joinHere(questionId: Int) = send(RecordingCommand.joinHere(questionId))
    fun leadFailedOk(questionId: Int) = send(RecordingCommand.leadFailedOk(questionId))
}
