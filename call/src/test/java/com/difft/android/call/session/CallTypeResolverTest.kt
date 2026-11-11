package com.difft.android.call.session

import com.difft.android.base.call.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test









class CallTypeResolverTest {

    private fun resolve(
        serverCallType: String?,
        participantCount: Int = 2,
        localCallType: String = CallType.ONE_ON_ONE.type,
        currentCallType: String = CallType.ONE_ON_ONE.type,
    ) = CallTypeResolver.resolve(serverCallType, participantCount, localCallType, currentCallType)

    
    
    

    @Test
    fun `server 1on1 with two participants stays one-on-one`() {
        assertEquals(CallType.ONE_ON_ONE, resolve(serverCallType = "1on1", participantCount = 2))
    }

    @Test
    fun `server 1on1 with a third participant downgrades to instant`() {
        assertEquals(CallType.INSTANT, resolve(serverCallType = "1on1", participantCount = 3))
    }

    
    
    @Test
    fun `server 1on1 with two dual-device users is instant`() {
        assertEquals(CallType.INSTANT, resolve(serverCallType = "1on1", participantCount = 4))
    }

    
    
    
    

    @Test
    fun `server group while still in the group stays group`() {
        assertEquals(
            CallType.GROUP,
            resolve(
                serverCallType = "group",
                participantCount = 5,
                localCallType = CallType.GROUP.type,
                currentCallType = CallType.GROUP.type,
            ),
        )
    }

    @Test
    fun `server group after leaving the group downgrades to instant`() {
        assertEquals(
            CallType.INSTANT,
            resolve(
                serverCallType = "group",
                participantCount = 5,
                
                localCallType = CallType.INSTANT.type,
                currentCallType = CallType.ONE_ON_ONE.type,
            ),
        )
    }

    @Test
    fun `server group is never upgraded from a non-group local type`() {
        assertEquals(
            CallType.INSTANT,
            resolve(serverCallType = "group", localCallType = CallType.ONE_ON_ONE.type),
        )
    }

    
    
    

    @Test
    fun `server instant is applied unconditionally`() {
        assertEquals(CallType.INSTANT, resolve(serverCallType = "instant", participantCount = 2))
    }

    
    
    
    @Test
    fun `instant is never downgraded back to one-on-one when the room empties out`() {
        assertEquals(
            CallType.INSTANT,
            resolve(
                serverCallType = "1on1",
                participantCount = 2,
                currentCallType = CallType.INSTANT.type,
            ),
        )
    }

    @Test
    fun `instant is never downgraded back to group`() {
        assertEquals(
            CallType.INSTANT,
            resolve(
                serverCallType = "group",
                participantCount = 5,
                localCallType = CallType.GROUP.type,
                currentCallType = CallType.INSTANT.type,
            ),
        )
    }

    
    
    

    
    
    @Test
    fun `unknown server value is treated as multi-party`() {
        assertEquals(CallType.INSTANT, resolve(serverCallType = "external", participantCount = 2))
    }

    
    
    @Test
    fun `server value with different casing is treated as unknown`() {
        assertEquals(CallType.INSTANT, resolve(serverCallType = "1ON1", participantCount = 2))
    }

    @Test
    fun `missing server value keeps running the local one-on-one participant rule`() {
        assertEquals(
            CallType.ONE_ON_ONE,
            resolve(serverCallType = null, participantCount = 2, currentCallType = CallType.ONE_ON_ONE.type),
        )
        assertEquals(
            CallType.INSTANT,
            resolve(serverCallType = null, participantCount = 3, currentCallType = CallType.ONE_ON_ONE.type),
        )
    }

    @Test
    fun `blank server value is treated the same as missing`() {
        assertEquals(
            CallType.INSTANT,
            resolve(serverCallType = "   ", participantCount = 3, currentCallType = CallType.ONE_ON_ONE.type),
        )
    }

    @Test
    fun `missing server value leaves a group call untouched`() {
        assertEquals(
            CallType.GROUP,
            resolve(
                serverCallType = null,
                participantCount = 9,
                localCallType = CallType.GROUP.type,
                currentCallType = CallType.GROUP.type,
            ),
        )
    }

    
    
    @Test
    fun `no server value and no current type decides nothing`() {
        assertNull(resolve(serverCallType = null, currentCallType = ""))
    }
}
