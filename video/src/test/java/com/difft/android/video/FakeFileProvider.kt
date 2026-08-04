package com.difft.android.video

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Test-only ContentProvider that serves a real file descriptor for a registered backing file.
 *
 * Required because ShadowContentResolver does NOT shadow openFileDescriptor: a registered
 * input-stream supplier can serve openInputStream but never a file descriptor. The fd path
 * therefore needs a real provider.
 *
 * Register with Robolectric.setupContentProvider(FakeFileProvider::class.java, AUTHORITY) —
 * ShadowContentResolver.registerProviderInternal does NOT work (no ProviderInfo attached, so
 * the framework's authority check rejects the call with SecurityException).
 */
class FakeFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        openFileCalls++
        val backing = files[uri] ?: throw FileNotFoundException("no backing file for $uri")
        return ParcelFileDescriptor.open(backing, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String = "video/mp4"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.difft.android.test.fakefiles"

        /** Backing files by URI. Populate before the call under test. */
        val files: MutableMap<Uri, File> = mutableMapOf()

        /** Number of openFile() invocations — asserted to be exactly 1 on the fd path. */
        var openFileCalls: Int = 0

        fun reset() {
            files.clear()
            openFileCalls = 0
        }
    }
}
