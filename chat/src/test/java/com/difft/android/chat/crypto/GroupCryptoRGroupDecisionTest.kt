package com.difft.android.chat.crypto

import org.junit.Test
import kotlin.test.assertEquals














class GroupCryptoRGroupDecisionTest {

    @Test
    fun `null stored version inserts`() {
        assertEquals(RGroupWriteDecision.INSERT, decideRGroupWrite(storedVersion = null, incomingVersion = 3))
    }

    @Test
    fun `incoming greater than stored overwrites`() {
        assertEquals(RGroupWriteDecision.OVERWRITE, decideRGroupWrite(storedVersion = 1, incomingVersion = 2))
    }

    @Test
    fun `incoming equal to stored skips`() {
        assertEquals(RGroupWriteDecision.SKIP, decideRGroupWrite(storedVersion = 2, incomingVersion = 2))
    }

    @Test
    fun `incoming lower than stored skips`() {
        assertEquals(RGroupWriteDecision.SKIP, decideRGroupWrite(storedVersion = 5, incomingVersion = 3))
    }

    @Test
    fun `stored zero incoming one overwrites`() {
        
        assertEquals(RGroupWriteDecision.OVERWRITE, decideRGroupWrite(storedVersion = 0, incomingVersion = 1))
    }

    @Test
    fun `stored zero incoming zero skips`() {
        
        assertEquals(RGroupWriteDecision.SKIP, decideRGroupWrite(storedVersion = 0, incomingVersion = 0))
    }

    @Test
    fun `negative incoming version skips`() {
        
        
        assertEquals(RGroupWriteDecision.SKIP, decideRGroupWrite(storedVersion = null, incomingVersion = -1))
        assertEquals(RGroupWriteDecision.SKIP, decideRGroupWrite(storedVersion = 5, incomingVersion = -1))
        assertEquals(RGroupWriteDecision.SKIP, decideRGroupWrite(storedVersion = null, incomingVersion = Int.MIN_VALUE))
    }

    @Test
    fun `large positive incoming version overwrites`() {
        assertEquals(RGroupWriteDecision.OVERWRITE, decideRGroupWrite(storedVersion = 5, incomingVersion = Int.MAX_VALUE))
    }
}
