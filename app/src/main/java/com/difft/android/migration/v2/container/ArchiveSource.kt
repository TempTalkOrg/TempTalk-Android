package com.difft.android.migration.v2.container

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Read-side counterpart to [ArchiveSink]: a source of the encrypted `.ttmig` container the restore
 * engine consumes, decoupling it from where the archive came from.
 *
 * `ContainerReader` verifies before it trusts, which is a two-pass read (MAC pass, then decrypt
 * pass) and so needs random access, while a SAF `content://` stream is forward-only. The
 * orchestrator therefore materializes such a source into a private work file; a source that already
 * is a local file skips the copy by returning a stable [asFile].
 */
interface ArchiveSource {
    /** Open a fresh stream of the container bytes. The caller owns + closes it. */
    fun openStream(): InputStream

    /**
     * If this source is already backed by a stable, randomly-accessible local file, return it so the
     * orchestrator can read it directly without copying. Returns null for forward-only sources (SAF).
     */
    fun asFile(): File? = null
}

/** [ArchiveSource] over a local [File]; exposes [asFile] so the orchestrator can skip the copy. */
class FileArchiveSource(private val file: File) : ArchiveSource {
    override fun openStream(): InputStream = FileInputStream(file)
    override fun asFile(): File = file
}

/**
 * [ArchiveSource] over a user-picked SAF document [Uri] — the production restore input. Forward-only,
 * so the orchestrator materializes it to a work file before the two-pass read.
 */
class SafArchiveSource(
    private val context: Context,
    private val uri: Uri,
) : ArchiveSource {
    override fun openStream(): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw IOException("ContentResolver.openInputStream returned null for $uri")
}
