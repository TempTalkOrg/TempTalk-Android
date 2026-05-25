package org.difft.app.database

/**
 * Thrown by [WCDBKeyManager] when the WCDB cipher key cannot be produced or persisted.
 *
 * Operationally equivalent to a corrupted database — the caller (`WCDB.db`) routes this
 * to the recovery flow (`DatabaseRecoveryPreferences.setRecoveryNeeded()` + restart).
 *
 * Distinct from [RuntimeException] subclasses thrown by WCDB itself so the recovery
 * code path in [WCDB] can disambiguate "cipher unavailable" from "data corrupt".
 */
class WCDBKeyUnavailableException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
