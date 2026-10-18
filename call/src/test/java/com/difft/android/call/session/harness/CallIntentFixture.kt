package com.difft.android.call.session.harness

import com.difft.android.base.call.CallType
import com.difft.android.call.CallIntent
import io.mockk.every
import io.mockk.mockk

/**
 * JVM [CallIntent]: a relaxed mock with the fields the session reads stubbed. The real class reads
 * a wrapped `android.content.Intent`, which needs Robolectric.
 */
internal object CallIntentFixture {
    fun build(
        action: CallIntent.Action = CallIntent.Action.START_CALL,
        callType: String = CallType.ONE_ON_ONE.type,
        conversationId: String? = "conv-1",
        clientCallId: String? = null,
        callerId: String = "caller-1",
        roomName: String = "Test Room",
    ): CallIntent = mockk(relaxed = true) {
        every { this@mockk.action } returns action
        every { this@mockk.callType } returns callType
        every { this@mockk.conversationId } returns conversationId
        every { this@mockk.clientCallId } returns clientCallId
        every { this@mockk.callerId } returns callerId
        every { this@mockk.roomName } returns roomName
    }
}
