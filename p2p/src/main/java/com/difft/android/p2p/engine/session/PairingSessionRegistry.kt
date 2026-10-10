package com.difft.android.p2p.engine.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * The pending reverse-pairing session: what this device registered about a peer it asked to connect
 * back, before it sent the request.
 *
 * Registration precedes the request on purpose — the peer may open `/prepare-upload` before this
 * device has processed the 200, so there must be no window in which the peer holds a valid token and
 * this registry is empty. Everything is keyed by a monotonic generation, which is the whole
 * invalidation mechanism: a cancel or a re-scan bumps or clears it, and every later query or
 * authorization for a dead generation fails rather than answering for the wrong session.
 *
 * Thread-safety: every method is `synchronized`, because authorizations arrive on connection threads
 * while the controller registers and waits on a coroutine. The observable state is published through
 * a [MutableStateFlow] so [awaitPhaseAtLeast] can see both a phase being reached and a generation
 * dying without polling.
 */
class PairingSessionRegistry {

    /** How far the registered session has got. Ordered: a later phase includes the earlier ones. */
    enum class Phase { PENDING, CONNECTED, UPLOADING }

    /** What [register] hands back; [generation] scopes every later query. [pairToken] is never logged. */
    data class Handle(val generation: Long, val pairToken: String)

    /** The answer [authorizePrepareUpload] gives the `/prepare-upload` handler. */
    sealed interface Decision {
        /** Token and PIN both match: the session moves to [Phase.CONNECTED]. */
        data object Accept : Decision

        /** Wrong or missing pair token — 403 `pair-token`. Does not count against the PIN cap. */
        data object RejectPairToken : Decision

        /** Right token, wrong PIN — 401, and one attempt against the cap. */
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

    /** The live session, or null when none is registered. Observed by [awaitPhaseAtLeast]. */
    private val live = MutableStateFlow<Session?>(null)

    /** The generation whose `/prepare-upload` was accepted, so the transfer key cannot be swapped under it. */
    private var acceptedGeneration: Long = NO_GENERATION

    private var nextGeneration: Long = 1L

    /**
     * Registers a pending pairing session, superseding whatever was there. [transferKey] is the key
     * from the peer's code, which the container is encrypted with; it and [pairToken] are never logged.
     */
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

    /** Drops [generation] if it is still the live one; a no-op once it has been superseded. */
    fun invalidate(generation: Long) = synchronized(lock) {
        if (live.value?.generation == generation) live.value = null
    }

    /** The phase of [generation], or null once it has been superseded or invalidated. */
    fun phase(generation: Long): Phase? = synchronized(lock) {
        live.value?.takeIf { it.generation == generation }?.phase
    }

    /** True while a pairing session is registered — what makes `/prepare-upload` take the pairing branch. */
    fun isActive(): Boolean = live.value != null

    /**
     * Suspends until [generation] has reached [phase]. Throws [PairingSuperseded] the instant that
     * generation is invalidated or superseded, so a caller can never park on a dead session.
     */
    suspend fun awaitPhaseAtLeast(generation: Long, phase: Phase) {
        live.first { session ->
            if (session == null || session.generation != generation) throw PairingSuperseded()
            session.phase >= phase
        }
    }

    /**
     * The `/prepare-upload` credential check for the pairing path: pair token first, then PIN
     * equality. A token mismatch is refused without counting an attempt — any host that reached the
     * port could otherwise spray tokens and lock the pairing out. A PIN mismatch counts, and hitting
     * [PinPolicy.MAX_PIN_ATTEMPTS] invalidates the session, matching the PIN policy's own cap.
     */
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

    /** The authorized `/upload` started: the pairing phase can no longer fail the transfer. */
    fun markUploading() {
        synchronized(lock) {
            val session = live.value ?: return
            if (session.phase < Phase.UPLOADING) live.value = session.copy(phase = Phase.UPLOADING)
        }
    }

    /**
     * The scanned transfer key of the generation whose `/prepare-upload` was accepted, or null when
     * none was accepted or that generation has since been superseded. Read once at body start, so a
     * re-scan returns null rather than swapping the key under a body that is already streaming — the
     * container then fails its HMAC loudly instead of decrypting garbage. Never logged.
     */
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

/** Thrown to a waiter whose pairing generation was invalidated or superseded while it was parked. */
class PairingSuperseded : IllegalStateException("pairing generation superseded")
