package com.difft.android.p2p.android.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.difft.android.p2p.engine.io.FileSource
import okio.Source
import okio.source

/**
 * [FileSource] over a picked Android content [Uri] (sender side). Reads name/size/MIME from
 * the [Context.getContentResolver]; [openRead] opens a fresh `InputStream` each time. The
 * SHA-256 is computed lazily on demand by the caller (engine [com.difft.android.p2p.engine.io.Sha256]).
 */
class UriFileSource(
    private val context: Context,
    private val uri: Uri,
    override val sha256: String? = null,
) : FileSource {

    override val fileName: String by lazy { queryName() }
    override val size: Long by lazy { querySize() }
    override val mimeType: String by lazy {
        context.contentResolver.getType(uri) ?: "application/octet-stream"
    }

    override fun openRead(): Source {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("[P2P] cannot open uri=$uri")
        return stream.source()
    }

    private fun queryName(): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun querySize(): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst()) return c.getLong(idx)
        }
        return 0L
    }
}
