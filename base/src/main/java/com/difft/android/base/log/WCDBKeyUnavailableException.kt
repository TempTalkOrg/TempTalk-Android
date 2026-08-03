package com.difft.android.base.log

/**
 * Thrown when the WCDB cipher key cannot be produced, read, or persisted.
 *
 * Lives in `:base` (not `:database`) so the shared fail-soft `CoroutineExceptionHandler`
 * (also in `:base`) can reference it — the module dependency is one-way `:database → :base`.
 *
 * Deliberately distinct from data-file corruption: classified as KEY_UNAVAILABLE (fail-soft),
 * never CORRUPT (wipe-eligible), so a transient keystore failure never triggers a wipe.
 */
class WCDBKeyUnavailableException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
