package com.difft.android.p2p.engine.io

/**
 * Markers the receiving side attaches to an exception so the transport can answer the peer correctly
 * without `:p2p` knowing anything about restores. `:app` implements them on its own exception types;
 * the transport only asks "is it one of these?".
 */

/**
 * The receiver ran out of storage **before** its commit boundary. Nothing was committed, so the
 * honest answer is a typed 403 `insufficient-storage` on the still-open connection, and the peer may
 * retry once space is free.
 */
interface TransferStorageFault

/**
 * The receiver's primary database was already switched when the failure happened. No terminal may be
 * published — not a 200, not a 403, no `/status` terminal — because this device cannot prove the
 * tail finished and neither "succeeded" nor "failed" would be true. The peer reads "result unknown".
 */
interface TransferCommittedUnconfirmed
