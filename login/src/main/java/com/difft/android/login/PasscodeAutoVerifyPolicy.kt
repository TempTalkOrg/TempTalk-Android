package com.difft.android.login










internal object PasscodeAutoVerifyPolicy {

    
    const val MANUAL_ATTEMPTS_LIMIT = 5

    
    const val AUTO_MISS_LIMIT = 20

    fun allowsAutoVerify(manualAttempts: Int, autoMisses: Int): Boolean =
        manualAttempts < MANUAL_ATTEMPTS_LIMIT && autoMisses < AUTO_MISS_LIMIT
}
