package com.difft.android.p2p.engine.upload

import com.difft.android.p2p.engine.model.MigrationHandshake

/**
 * The receiver-side authoritative pre-transfer gate: given the sending device's [MigrationHandshake]
 * (its source identity), decide whether this device accepts the migration before any session is
 * created or any container byte is read — environment isolation (test↔test / prod↔prod), account
 * match, format/version compatibility.
 *
 * Only the seam lives here: `:p2p` knows nothing about environments, app versions or the container
 * format contract, so [PrepareUploadHandler] calls this before
 * [com.difft.android.p2p.engine.session.SessionManager.createSession] and the `:app` migration layer
 * supplies the implementation. A null validator means "no migration gate" — the generic
 * file-transfer path never carries a [MigrationHandshake].
 *
 * Defense in depth, not the only check: the account and format checks also run later inside the
 * HMAC'd container manifest (`RestoreOrchestrator` / `ContainerReader`), so a forged handshake can
 * only be rejected earlier or still be caught by the sealed manifest. Environment isolation is the
 * one check the manifest does not carry, so this gate is what makes the import path environment-safe.
 */
fun interface MigrationHandshakeValidator {

    /**
     * @return [MigrationGateDecision.Accept] to proceed to session creation, or a
     *   [MigrationGateDecision.Reject] carrying a typed [MigrationRejectReason], which the handler
     *   maps onto a 403 so the sender shows a specific message and no bytes flow. Implementations
     *   must not throw — a thrown exception becomes an internal 500 instead of a typed reject.
     */
    fun validate(handshake: MigrationHandshake): MigrationGateDecision
}

/** Result of the [MigrationHandshakeValidator] gate. */
sealed interface MigrationGateDecision {
    /** The source is compatible — proceed to accept the upload session. */
    data object Accept : MigrationGateDecision

    /** The source is incompatible — reject the `prepare-upload` (→ 403) with [reason]. */
    data class Reject(val reason: MigrationRejectReason) : MigrationGateDecision
}

/**
 * Typed reasons a migration handshake is rejected. The [wire] string rides the 403 response so the
 * sender can show a specific message; the enum is platform-neutral so the iOS port mirrors it.
 */
enum class MigrationRejectReason(val wire: String) {
    /** test↔prod environment mismatch — the security-critical isolation reject. */
    ENV_MISMATCH("environment-mismatch"),

    /** The source account does not match the receiver's logging-in account. */
    ACCOUNT_MISMATCH("account-mismatch"),

    /** Cross-major container/app version — a transfer the reader would later reject. */
    VERSION_INCOMPATIBLE("version-incompatible"),

    /**
     * The carried identity differs from the receiver's installed identity. Only raised by
     * verify-and-skip ends (Desktop secondary devices, where installing a foreign identity would
     * break their primary-device link); Android/iOS install ends never emit it, but every sender
     * must recognize the wire token to show a specific message instead of a generic failure.
     */
    IDENTITY_MISMATCH("identity-mismatch"),

    /**
     * The two ends disagree about the same-device no-login credential handoff — the sender declared it
     * and this receiver is not running it, or the reverse. Safety-critical: without this reject a
     * sender could hand its credential to a receiver that will never install it, get a 200, and wipe.
     */
    HANDOFF_UNSUPPORTED("handoff-unsupported"),

    /** The receiver cannot run the no-login handoff — it has no handoff gate wired up. */
    HANDOFF_DISABLED("handoff-disabled"),

    /**
     * The handoff receiver already has an account / local data. The net-install red line, moved to the
     * fail-fast front line: on this path the receiver's entire premise is "I have nothing here".
     */
    TARGET_NOT_EMPTY("target-not-empty"),

    /**
     * Reverse pairing: the `pairToken` does not match the pending pairing session, or the request
     * carries one when no session is pending. The sender re-scans the code.
     */
    PAIR_TOKEN("pair-token"),

    /**
     * The receiving device does not have room for this transfer, or ran out of it before its commit
     * boundary. Nothing was committed; the sender may retry once space is free.
     */
    INSUFFICIENT_STORAGE("insufficient-storage"),
}
