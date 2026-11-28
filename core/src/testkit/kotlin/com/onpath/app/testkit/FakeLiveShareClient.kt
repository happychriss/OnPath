package com.onpath.app.testkit

import com.onpath.app.live.LiveShareClient
import com.onpath.app.live.LiveSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The live sharing for a state holder under test (test kit): the calls are kept in [calls], in order
 * ("start:<name>", "stop", "seen"); the test plays the live lane with [publish].
 */
class FakeLiveShareClient : LiveShareClient {
    private val _live = MutableStateFlow(LiveSnapshot.OFF)
    override val live: StateFlow<LiveSnapshot> get() = _live
    val calls = ArrayList<String>()

    override fun liveStart(name: String) { calls += "start:$name" }
    override fun liveStop() { calls += "stop" }
    override fun liveSeen() { calls += "seen" }
    override fun liveRename(name: String) { calls += "rename:$name" }

    /** The live lane's side: what the sharing shows now. */
    fun publish(s: LiveSnapshot) { _live.value = s }
}
