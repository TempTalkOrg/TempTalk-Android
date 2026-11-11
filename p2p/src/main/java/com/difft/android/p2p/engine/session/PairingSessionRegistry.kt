package com.difft.android.p2p.engine.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
















class PairingSessionRegistry {

    
    enum class Phase { PENDING, CONNECTED, UPLOADING }

    
    data class Handle(val generation: Long, val pairToken: String)

    
    sealed interface Decision {
        
        data object Accept : Decision

        
        data object RejectPairToken : Decision

        
        data object RejectPin : Decision
    }

    private data class Session(
        val generation: Long,
        val pin: String,
        val transferKey: String?,
        val pairToken: String,
        val phase: Phase,
        val pinAttempts: Int,
    )

    private val lock = Any()

    
    private val live = MutableStateFlow<Session?>(null)

    
    private var acceptedGeneration: Long = NO_GENERATION

    private var nextGeneration: Long = 1L

    



    fun register(pin: String, transferKey: String?, pairToken: String): Handle = synchronized(lock) {
        val generation = nextGeneration++
        live.value = Session(
            generation = generation,
            pin = pin,
            transferKey = transferKey,
            pairToken = pairToken,
            phase = Phase.PENDING,
            pinAttempts = 0,
        )
        Handle(generation = generation, pairToken = pairToken)
    }

    
    fun invalidate(generation: Long) = synchronized(lock) {
        if (live.value?.generation == generation) live.value = null
    }

    
    fun phase(generation: Long): Phase? = synchronized(lock) {
        live.value?.takeIf { it.generation == generation }?.phase
    }

    
    fun isActive(): Boolean = live.value != null

    



    suspend fun awaitPhaseAtLeast(generation: Long, phase: Phase) {
        live.first { session ->
            if (session == null || session.generation != generation) throw PairingSuperseded()
            session.phase >= phase
        }
    }

    





    fun authorizePrepareUpload(pairToken: String?, pin: String?): Decision = synchronized(lock) {
        val session = live.value ?: return Decision.RejectPairToken
        if (pairToken == null || !constantTimeEquals(pairToken, session.pairToken)) {
            return Decision.RejectPairToken
        }
        if (pin == null || !constantTimeEquals(pin, session.pin)) {
            val attempts = session.pinAttempts + 1
            live.value = if (attempts >= PinPolicy.MAX_PIN_ATTEMPTS) null else session.copy(pinAttempts = attempts)
            return Decision.RejectPin
        }
        acceptedGeneration = session.generation
        if (session.phase < Phase.CONNECTED) live.value = session.copy(phase = Phase.CONNECTED)
        Decision.Accept
    }

    
    fun markUploading() {
        synchronized(lock) {
            val session = live.value ?: return
            if (session.phase < Phase.UPLOADING) live.value = session.copy(phase = Phase.UPLOADING)
        }
    }

    





    fun acceptedTransferKey(): String? = synchronized(lock) {
        live.value?.takeIf { it.generation == acceptedGeneration }?.transferKey
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var result = 0
        for (i in x.indices) result = result or (x[i].toInt() xor y[i].toInt())
        return result == 0
    }

    private companion object {
        const val NO_GENERATION = -1L
    }
}


class PairingSuperseded : IllegalStateException("pairing generation superseded")
