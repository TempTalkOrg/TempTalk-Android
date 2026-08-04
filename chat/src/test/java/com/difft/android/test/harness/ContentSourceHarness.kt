package com.difft.android.test.harness

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * H1 — registers readable content sources on the shadow [android.content.ContentResolver] and
 * records every open.
 *
 * Registration goes through `registerInputStreamSupplier`, never `registerInputStream`: the latter
 * hands out one stream instance, and the send chain opens the same URI more than once (a
 * readability probe followed by the attachment copy), so the second open would receive a stream that
 * the first `use {}` already closed.
 *
 * The supplier doubles as the call recorder because `ShadowContentResolver` keeps no log of reads —
 * its statement log only covers insert / update / delete / query. [opens] is therefore the only way
 * to assert "these bytes really came through the resolver rather than from a bare path".
 */
class ContentSourceHarness(context: Context) {

    private val shadowResolver = shadowOf(context.contentResolver)

    /** Every URI opened through the resolver, in call order. Duplicates are kept on purpose. */
    val opens: MutableList<Uri> = mutableListOf()

    /** Registers [uri] as readable, serving a fresh stream over [bytes] on each open. */
    fun registerReadable(uri: Uri, bytes: ByteArray): ContentSourceHarness = apply {
        shadowResolver.registerInputStreamSupplier(uri) {
            opens += uri
            ByteArrayInputStream(bytes) as InputStream
        }
    }

    /**
     * H2 — registers [uri] as unreadable in the exact shape a denied read has on a device: a
     * `FileNotFoundException` whose message is the absolute path, caused by `ErrnoException(EACCES)`.
     *
     * Leaving the URI unregistered would NOT express this: the shadow resolver then hands back a
     * stub stream whose `read()` throws `UnsupportedOperationException`, so no
     * `FileNotFoundException` and no errno ever reach the code under test — and rows that assert on
     * either would silently test nothing.
     *
     * [fakeAbsPath] must really be a path: it is the input a log-redaction assertion needs.
     */
    fun registerUnreadable(uri: Uri, fakeAbsPath: String): ContentSourceHarness = apply {
        shadowResolver.registerInputStreamSupplier(uri) {
            opens += uri
            throw FileNotFoundException("$fakeAbsPath: open failed: EACCES (Permission denied)")
                .apply { initCause(ErrnoException("open", OsConstants.EACCES)) }
        }
    }
}
