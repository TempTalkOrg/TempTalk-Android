package com.difft.android.migration.v2.restore

import android.content.Context
import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.google.gson.Gson
import org.difft.app.database.WCDBKeyManager
import java.io.File

/**
 * The single source of truth for an in-progress promote. Written to
 * `filesDir/migration_promote.marker` before any irreversible promote step and cleared only once the
 * promote completes, so its presence on a cold start means a promote was interrupted and must be
 * re-done idempotently. Restore only runs on a net install, so re-doing or discarding a half-promote
 * can never destroy existing user data.
 *
 * The staging cipher key is never stored in plaintext: [protectedStagingKeyB64] is the Keystore-GCM
 * blob from [WCDBKeyManager.protectKey], so a marker leaked off the device is useless without the
 * device Keystore. In debug builds the staging DB is plaintext, matching the real DB, so there is no
 * key to protect and promote skips the key install.
 *
 * Serialized to JSON via Gson, which is key-based: reordering fields is safe, renaming is breaking.
 */
data class PromoteMarker(
    val stagingDbPath: String,
    val protectedStagingKeyB64: String?,
    val attachmentsStagingDir: String,
    val realDbPath: String,
    val realAttachmentDir: String,
) {
    /**
     * Recover the raw staging cipher key from the protected blob, or null when the staging DB is
     * plaintext and no key was installed.
     */
    fun stagingKey(): ByteArray? =
        protectedStagingKeyB64?.let { WCDBKeyManager.unprotectKey(Base64.decode(it, Base64.NO_WRAP)) }

    companion object {
        /**
         * Build a marker, protecting `stagingKey` via the Keystore so it never lands on disk in
         * plaintext. A null key yields a null [protectedStagingKeyB64] — nothing to install at promote.
         */
        fun create(
            stagingDbPath: String,
            stagingKey: ByteArray?,
            attachmentsStagingDir: String,
            realDbPath: String,
            realAttachmentDir: String,
        ): PromoteMarker {
            val protectedB64 = stagingKey?.let {
                Base64.encodeToString(WCDBKeyManager.protectKey(it), Base64.NO_WRAP)
            }
            return PromoteMarker(
                stagingDbPath = stagingDbPath,
                protectedStagingKeyB64 = protectedB64,
                attachmentsStagingDir = attachmentsStagingDir,
                realDbPath = realDbPath,
                realAttachmentDir = realAttachmentDir,
            )
        }
    }
}

/**
 * Atomic on-disk store for the [PromoteMarker]: a write goes to a `.tmp` and is then renamed, so a
 * crash mid-write can never leave a half-marker that crash recovery would misread.
 */
class PromoteMarkerStore(private val context: Context) {

    private val gson = Gson()

    private val markerFile: File get() = File(context.filesDir, MARKER_FILE_NAME)
    private val tmpFile: File get() = File(context.filesDir, MARKER_TMP_NAME)

    /** True if a promote is in progress (marker present). */
    fun exists(): Boolean = markerFile.exists()

    /**
     * Outcome of [readChecked], distinguishing no marker on disk from a marker file that exists but
     * cannot be parsed. The latter must not be treated as "no marker", which would leave a half-promote
     * stuck forever while every cold start silently no-ops; cold-start recovery instead clears it and
     * discards staging, aborting to the clean state.
     */
    sealed interface ReadResult {
        /** No marker file on disk — the normal-launch case. */
        data object Absent : ReadResult

        /** A valid marker was parsed. */
        data class Present(val marker: PromoteMarker) : ReadResult

        /** A marker file exists but failed to parse (Gson → null / threw). Unrecoverable-but-known. */
        data object Corrupt : ReadResult
    }

    /** Atomically persist [marker]. */
    fun write(marker: PromoteMarker) {
        val json = gson.toJson(marker)
        try {
            tmpFile.outputStream().use { os ->
                os.write(json.toByteArray(Charsets.UTF_8))
                os.flush()
                os.fd.sync()
            }
            if (!tmpFile.renameTo(markerFile)) {
                markerFile.delete()
                if (!tmpFile.renameTo(markerFile)) {
                    error("rename $MARKER_TMP_NAME → $MARKER_FILE_NAME failed")
                }
            }
            L.i { "[MigrationV2][Promote] marker written stagingDb=${marker.stagingDbPath}" }
        } catch (e: Throwable) {
            tmpFile.delete()
            throw e
        }
    }

    /** Read the marker, or null when none / unparseable. */
    fun read(): PromoteMarker? =
        when (val r = readChecked()) {
            is ReadResult.Present -> r.marker
            ReadResult.Absent, ReadResult.Corrupt -> null
        }

    /**
     * Read the marker, distinguishing [ReadResult.Absent] from [ReadResult.Corrupt]. Gson returns null
     * without throwing for an empty or "null"-literal body, so a non-throwing null counts as corrupt
     * too: an existing file that yields no marker is unparseable by definition.
     */
    fun readChecked(): ReadResult {
        if (!markerFile.exists()) return ReadResult.Absent
        return try {
            val marker = gson.fromJson(markerFile.readText(Charsets.UTF_8), PromoteMarker::class.java)
            if (marker == null) {
                L.e { "[MigrationV2][Promote] marker file present but parsed to null → corrupt" }
                ReadResult.Corrupt
            } else {
                ReadResult.Present(marker)
            }
        } catch (e: Throwable) {
            L.e { "[MigrationV2][Promote] marker unreadable (file present) → corrupt: ${e.stackTraceToString()}" }
            ReadResult.Corrupt
        }
    }

    /** Delete the marker. Idempotent. */
    fun clear() {
        runCatching { markerFile.delete() }
        runCatching { tmpFile.delete() }
        L.i { "[MigrationV2][Promote] marker cleared" }
    }

    companion object {
        const val MARKER_FILE_NAME = "migration_promote.marker"
        const val MARKER_TMP_NAME = "migration_promote.marker.tmp"
    }
}
