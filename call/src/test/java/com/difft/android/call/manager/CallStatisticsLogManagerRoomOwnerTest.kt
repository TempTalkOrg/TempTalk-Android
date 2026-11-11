package com.difft.android.call.manager

import com.difft.android.call.state.OnGoingCallStateManager
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test






class CallStatisticsLogManagerRoomOwnerTest {

    private val manager = CallStatisticsLogManager(OnGoingCallStateManager(), mockk(relaxed = true))

    @Test
    fun `a claim starts the room ids empty`() {
        manager.setRoomId("stale")
        manager.setRoomLocalId("stale-local")

        manager.claimRoom(1L)

        assertNull(field("currentRoomId"))
        assertNull(field("currentRoomLocalId"))
    }

    @Test
    fun `a release by a session that does not own the ids is a no-op, the owner's clears them`() {
        manager.claimRoom(1L)
        manager.claimRoom(2L)
        manager.setRoomId("r2")
        manager.setRoomLocalId("l2")

        manager.releaseRoom(1L)

        assertEquals("r2", field("currentRoomId"))
        assertEquals("l2", field("currentRoomLocalId"))

        manager.releaseRoom(2L)

        assertNull(field("currentRoomId"))
        assertNull(field("currentRoomLocalId"))
    }

    private fun field(name: String): Any? = CallStatisticsLogManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }
        .get(manager)
}
