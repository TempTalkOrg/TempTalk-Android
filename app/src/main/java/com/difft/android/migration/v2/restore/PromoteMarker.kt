package com.difft.android.migration.v2.restore

import android.content.Context
import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.google.gson.Gson
import org.difft.app.database.WCDBKeyManager
import java.io.File















data class PromoteMarker(
    val stagingDbPath: String,
    val protectedStagingKeyB64: String?,
    val attachmentsStagingDir: String,
    val realDbPath: String,
    val realAttachmentDir: String,
) {
    



    fun stagingKey(): ByteArray? =
        protectedStagingKeyB64?.let { WCDBKeyManager.unprotectKey(Base64.decode(it, Base64.NO_WRAP)) }

    companion object {
        



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





class PromoteMarkerStore(private val context: Context) {

    private val gson = Gson()

    private val markerFile: File get() = File(context.filesDir, MARKER_FILE_NAME)
    private val tmpFile: File get() = File(context.filesDir, MARKER_TMP_NAME)

    
    fun exists(): Boolean = markerFile.exists()

    





    sealed interface ReadResult {
        
        data object Absent : ReadResult

        
        data class Present(val marker: PromoteMarker) : ReadResult

        
        data object Corrupt : ReadResult
    }

    
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

    
    fun read(): PromoteMarker? =
        when (val r = readChecked()) {
            is ReadResult.Present -> r.marker
            ReadResult.Absent, ReadResult.Corrupt -> null
        }

    




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
