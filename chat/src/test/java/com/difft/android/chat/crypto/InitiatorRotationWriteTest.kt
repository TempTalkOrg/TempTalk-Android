package com.difft.android.chat.crypto

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [shouldApplyInitiatorRotation], the gate of [GroupCryptoRepo.setRotatedRGroup]: the rotation
 * initiator's key is written over an equal version (server omitted keyVersion) but never over a
 * newer one received meanwhile, now that a rotation can finish after its page is gone.
 */
class InitiatorRotationWriteTest {

    @Test
    fun `no stored key or a newer or equal version is written`() {
        assertTrue(shouldApplyInitiatorRotation(storedVersion = null, version = 1))
        assertTrue(shouldApplyInitiatorRotation(storedVersion = 3, version = 4))
        assertTrue(shouldApplyInitiatorRotation(storedVersion = 3, version = 3))
    }

    @Test
    fun `a stale version is ignored`() {
        assertFalse(shouldApplyInitiatorRotation(storedVersion = 5, version = 4))
    }
}
