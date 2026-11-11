package com.difft.android.p2p.engine.session

import java.security.SecureRandom















class PinPolicy(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = Any()

    @Volatile private var pin: String? = null
    @Volatile private var issuedAt: Long = 0L
    @Volatile private var consumed: Boolean = false

    
    private var failedAttempts: Int = 0

    
    fun issue(): String = synchronized(lock) {
        val value = "%06d".format(random.nextInt(1_000_000))
        pin = value
        issuedAt = clock()
        consumed = false
        failedAttempts = 0
        value
    }

    
    fun current(): String? = pin

    








    fun validateAndConsume(candidate: String?): Boolean = synchronized(lock) {
        if (!liveMatch(candidate)) return false
        consumed = true
        true
    }

    




    fun matches(candidate: String?): Boolean = synchronized(lock) { liveMatch(candidate) }

    
    fun touch() = synchronized(lock) { if (pin != null) issuedAt = clock() }

    
    private fun liveMatch(candidate: String?): Boolean {
        val active = pin ?: return false
        if (consumed) return false
        if (clock() - issuedAt > ttlMillis) {
            clearState()
            return false
        }
        if (candidate == null || !constantTimeEquals(candidate, active)) {
            failedAttempts++
            
            
            if (failedAttempts >= MAX_PIN_ATTEMPTS) clearState()
            return false
        }
        return true
    }

    
    fun reset() = synchronized(lock) { clearState() }

    
    private fun clearState() {
        pin = null
        consumed = false
        issuedAt = 0L
        failedAttempts = 0
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var result = 0
        for (i in x.indices) result = result or (x[i].toInt() xor y[i].toInt())
        return result == 0
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 5 * 60 * 1000L 

        
        const val MAX_PIN_ATTEMPTS: Int = 5
    }
}
