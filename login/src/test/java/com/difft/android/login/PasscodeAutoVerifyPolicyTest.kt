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
        // Guessing by typing and clearing without submit must not be unlimited (#1412).
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
        // A 10-digit passcode is tested at 4..9 digits on the way: 6 misses per entry.
        val missesPerLongestEntry = 10 - 4
        assertTrue(PasscodeAutoVerifyPolicy.allowsAutoVerify(0, 3 * missesPerLongestEntry))
    }
}
