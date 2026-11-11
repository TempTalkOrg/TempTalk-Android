package com.difft.android.call

import com.difft.android.base.call.CallRole
import com.difft.android.base.user.CallConfig
import com.difft.android.call.data.VoicePreset
import com.difft.android.call.session.CallSession
import com.difft.android.call.session.CallSessionParams
import com.difft.android.call.session.resolveCallSession
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test







class LCallActivitySessionResolveTest {

    private val live = mockk<CallSession> {
        every { params } returns CallSessionParams(
            e2eeEnable = false,
            callIntent = mockk(relaxed = true),
            callConfig = CallConfig(),
            callRole = CallRole.CALLER,
            initialVoicePreset = VoicePreset.ORIGINAL,
            launchKey = LIVE_KEY,
        )
    }

    @Test
    fun `a page with no key returns to the live session`() {
        assertSame(live, resolveCallSession(live, sessionKey = null))
    }

    @Test
    fun `the live session's own launch attaches to it`() {
        assertSame(live, resolveCallSession(live, sessionKey = LIVE_KEY))
    }

    @Test
    fun `a page launched for another session gets none`() {
        assertNull(resolveCallSession(live, sessionKey = "stale-key"))
    }

    @Test
    fun `no live session means no session, with or without a key`() {
        assertNull(resolveCallSession(null, sessionKey = null))
        assertNull(resolveCallSession(null, sessionKey = LIVE_KEY))
    }

    private companion object {
        const val LIVE_KEY = "live-key"
    }
}
