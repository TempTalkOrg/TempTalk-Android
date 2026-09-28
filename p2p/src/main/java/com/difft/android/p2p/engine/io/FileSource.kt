package com.difft.android.p2p.engine.io

import okio.Source

/**
 * A readable file the sender streams to the receiver. Transport-agnostic: backed by an
 * Android `Uri` (P2P send) today, could be a plain `File` or an in-memory buffer in tests
 * or a future file-fallback path. Okio `Source` keeps the engine free of Android imports.
 */
interface FileSource {
    val fileName: String
    val size: Long
    val mimeType: String

    /** Optional precomputed SHA-256 (hex). Null if the caller hasn't hashed it yet. */
    val sha256: String?

    /** Opens a fresh stream over the content. Caller closes it. */
    fun openRead(): Source
}
