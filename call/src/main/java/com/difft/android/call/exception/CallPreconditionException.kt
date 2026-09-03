package com.difft.android.call.exception

/**
 * A local precondition for connecting is unmet, so no connection candidate can succeed.
 *
 * Distinct from transport failures on purpose: connection failover classifies unknown
 * failures as this-node-specific and advances to the next candidate, so a whole-attempt
 * blocker must announce itself with a type instead of relying on a catch-all. Used for
 * fail-closed guards (e.g. proxy media relay forced without a pinnable TURN certificate)
 * whose answer is identical on every node.
 */
class CallPreconditionException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)
