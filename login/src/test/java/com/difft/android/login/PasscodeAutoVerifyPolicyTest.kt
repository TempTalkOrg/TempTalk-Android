package com.difft.android.login

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasscodeAutoVerifyPolicyTest {

    @Test
    fun `fresh lock screen verifies as you type`() {
        assertTrue(PasscodeAutoVerifyPolicy.allowsAutoVerify(manualAttempts = 0, autoMisses = 0))
    }

    @Test
    fun `wrong as-you-type values are capped`() {
        
        assertTrue(PasscodeAutoVerifyPolicy.allowsAutoVerify(0, PasscodeAutoVerifyPolicy.AUTO_MISS_LIMIT - 1))
        assertFalse(PasscodeAutoVerifyPolicy.allowsAutoVerify(0, PasscodeAutoVerifyPolicy.AUTO_MISS_LIMIT))
        assertFalse(PasscodeAutoVerifyPolicy.allowsAutoVerify(0, PasscodeAutoVerifyPolicy.AUTO_MISS_LIMIT + 5))
    }

    @Test
    fun `manual retry ladder still stops as-you-type checks`() {
        assertTrue(PasscodeAutoVerifyPolicy.allowsAutoVerify(PasscodeAutoVerifyPolicy.MANUAL_ATTEMPTS_LIMIT - 1, 0))
        assertFalse(PasscodeAutoVerifyPolicy.allowsAutoVerify(PasscodeAutoVerifyPolicy.MANUAL_ATTEMPTS_LIMIT, 0))
    }

    @Test
    fun `entering the longest passcode fits the budget several times`() {
        
        val missesPerLongestEntry = 10 - 4
        assertTrue(PasscodeAutoVerifyPolicy.allowsAutoVerify(0, 3 * missesPerLongestEntry))
    }
}
