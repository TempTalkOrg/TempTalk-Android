package com.difft.android.test.fakes

import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MigrationMessagePause
import kotlinx.coroutines.runBlocking





class TestMessagingPause {

    val gate = MessagingGate()

    
    val store = RecordingPreferencesDataStore()
    val pause = MigrationMessagePause(store, gate)

    
    fun open(): TestMessagingPause = apply {
        runBlocking { pause.load() }
        check(gate.isOpen) { "gate did not open" }
    }

    
    fun pauseMessaging(owner: String = OWNER): TestMessagingPause = apply {
        check(runBlocking { pause.begin(owner, "scope", "cred") }) { "pause did not begin" }
    }

    
    fun resumeMessaging(): TestMessagingPause = apply {
        runBlocking { pause.clear(owner = null, reason = "test") }
        check(gate.isOpen) { "gate did not reopen" }
    }

    companion object {
        const val OWNER = "00000000-0000-0000-0000-000000001382"
    }
}
