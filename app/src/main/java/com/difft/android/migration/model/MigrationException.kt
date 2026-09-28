package com.difft.android.migration.model

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
}
