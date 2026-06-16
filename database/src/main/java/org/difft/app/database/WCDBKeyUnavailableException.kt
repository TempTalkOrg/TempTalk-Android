package org.difft.app.database

/**
 * Thrown by [WCDBKeyManager] when the WCDB cipher key cannot be produced or persisted.
 *
 * Operationally equivalent to a corrupted database — when it surfaces from the `WCDB.db`
 * lazy open, `WCDB.probeHealthy()` maps it to `DbHealth.CORRUPT` so MainActivity's gate
 * routes through the recovery flow.
 *
 * Distinct from [RuntimeException] subclasses thrown by WCDB itself so the recovery
 * code path can disambiguate "cipher unavailable" from "data corrupt".
 */
class WCDBKeyUnavailableException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
