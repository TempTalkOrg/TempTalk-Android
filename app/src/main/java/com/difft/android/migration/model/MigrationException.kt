package com.difft.android.migration.model

import com.difft.android.p2p.engine.io.TransferCommittedUnconfirmed
import com.difft.android.p2p.engine.io.TransferStorageFault

/**
 * Sealed throwable hierarchy for migration-internal failures the caller may want to
 * discriminate. Generic platform exceptions (`IOException`, `SecurityException`,
 * `GeneralSecurityException`, `CancellationException`) are not wrapped — they are translated at
 * the boundary by `MigrationError.from(throwable)`.
 */
sealed class MigrationException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** WCDB snapshot step exhausted retries (truncateCheckpoint kept reporting SQLITE_BUSY). */
    class SnapshotFailed(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    /** Encryption pipeline failed mid-stream (e.g. cipher init, write to SAF output). */
    class EncryptionFailed(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    /**
     * HMAC verification of an encrypted backup failed — tampered file or wrong passphrase.
     *
     * CTR mode streams plaintext to the output file before the trailing HMAC can be checked, so
     * the decryptor deletes that output in its `finally` block: a successful return implies the
     * output is authenticated and a failure leaves no plaintext behind. A process kill inside
     * that window can still orphan an unverified file; `RestoreInProgressFlag` startup detection
     * cleans it up.
     */
    class HmacMismatch(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    /** Decryption failed after HMAC passed (cipher-level error — should be very rare). */
    class DecryptionFailed(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    /**
     * The stream ended, or its transport failed, before the trailer was read: the peer went away or
     * the link dropped. Says nothing about the data itself — nothing was verified, nothing was kept.
     */
    class StreamInterrupted(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    /**
     * The sink was closed under the writer before the container ended: the uploader draining it
     * lost its connection (the peer hung up). Says nothing about the data being written.
     */
    class SinkInterrupted(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    /** Restore-side atomic file rename / fsync failed. */
    class RestoreFailed(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    /** Restore-side sign-in retry exhausted. */
    class SignInFailed(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    /**
     * The promote can never complete and was safely abandoned back to the clean net-install state.
     * Unlike [RestoreFailed] (retryable — the marker is kept so cold start retries) this is
     * terminal: it arises when the staging cipher key cannot be recovered from the promote marker
     * (the AndroidKeystore alias was invalidated), which would otherwise loop on every cold start.
     * Restore only runs on a net install, so the real DB is still empty and clearing the marker +
     * discarding staging is safe. The caller re-prompts restore rather than retrying.
     */
    class RestoreAbortedCleanState(message: String, cause: Throwable? = null) : MigrationException(message, cause)

    /**
     * A write ran out of storage before the restore's commit boundary. Nothing was committed, so the
     * transfer can be refused honestly and retried once space is free.
     *
     * A [MigrationException] rather than an [java.io.IOException] on purpose: `ContainerReader` wraps
     * every `IOException` into [StreamInterrupted] (which classifies as a lost connection) and passes
     * a `MigrationException` through untouched, so the "out of space" classification survives the
     * container layer with no edit to it.
     */
    class OutOfSpace(message: String, cause: Throwable? = null) :
        MigrationException(message, cause), TransferStorageFault

    /**
     * The primary database was already switched when the failure happened, and the tail after it is
     * not proven complete. The data is kept and no terminal may be published in either direction:
     * neither "succeeded" nor "failed" would be true, so the peer is left at "result unknown" and
     * this device finishes the promote on its next cold start.
     */
    class CommittedUnconfirmed(message: String, cause: Throwable? = null) :
        MigrationException(message, cause), TransferCommittedUnconfirmed
}
