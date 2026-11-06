package com.difft.android.test.fakes

import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MigrationMessagePause
import kotlinx.coroutines.runBlocking

/**
 * A real [MessagingGate] driven through its only writer, [MigrationMessagePause], over an
 * in-memory store (#1382). A new instance is still loading, so messaging starts closed.
 */
class TestMessagingPause {

    val gate = MessagingGate()

    /** The app_state the pause lives in; the pending verdict shares it (#1382). */
    val store = RecordingPreferencesDataStore()
    val pause = MigrationMessagePause(store, gate)

    /** Completes the load with no stored pause: messaging opens. */
    fun open(): TestMessagingPause = apply {
        runBlocking { pause.load() }
        check(gate.isOpen) { "gate did not open" }
    }

    /** Starts [owner]'s pause; the gate refuses new tickets from here on. */
    fun pauseMessaging(owner: String = OWNER): TestMessagingPause = apply {
        check(runBlocking { pause.begin(owner, "scope", "cred") }) { "pause did not begin" }
    }

    /** Clears any pause: messaging opens again. */
    fun resumeMessaging(): TestMessagingPause = apply {
        runBlocking { pause.clear(owner = null, reason = "test") }
        check(gate.isOpen) { "gate did not reopen" }
    }

    companion object {
        const val OWNER = "00000000-0000-0000-0000-000000001382"
    }
}
