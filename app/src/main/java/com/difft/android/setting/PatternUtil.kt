package com.difft.android.setting

import com.difft.android.base.widget.PatternLockView
import com.difft.android.login.PasscodeUtil

object PatternUtil {
    
    /**
     * Convert pattern to string representation
     */
    fun patternToString(pattern: List<PatternLockView.Dot>): String {
        return pattern.joinToString(",") { it.id.toString() }
    }
    
    /**
     * Create pattern hash using PBKDF2WithHmacSHA256 (same as passcode)
     */
    fun createPatternHash(pattern: List<PatternLockView.Dot>): String {
        val patternString = patternToString(pattern)
        val (salt, hashedPattern) = PasscodeUtil.createSaltAndHashByPassword(patternString)
        return "${hashedPattern}:${salt}"
    }
    
    /**
     * Verify pattern against stored hash
     */
    fun verifyPattern(storedPatternHash: String, patternAttempt: List<PatternLockView.Dot>): Boolean {
        val patternString = patternToString(patternAttempt)
        val hash = storedPatternHash.split(":")[0]
        val salt = storedPatternHash.split(":")[1]
        return PasscodeUtil.verifyPassword(hash, salt, patternString)
    }
    
    /**
     * Check if pattern is valid (at least 4 dots)
     */
    fun isValidPattern(pattern: List<PatternLockView.Dot>): Boolean {
        return pattern.size >= 4
    }
}
