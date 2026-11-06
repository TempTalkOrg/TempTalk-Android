package com.difft.android.base.utils

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SharedUriGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val appRoot: File by lazy { tmp.newFolder("data", "org.example.app") }
    private val roots by lazy { listOf(appRoot) }

    private fun fileUri(path: String): Uri = Uri.fromFile(File(path))

    @Test
    fun `file uri into app storage is rejected`() {
        val privateFile = File(appRoot, "databases/messages.db")
        assertTrue(SharedUriGuard.pointsIntoAppStorage(fileUri(privateFile.path), roots))
    }

    @Test
    fun `path traversal back into app storage is rejected`() {
        val sibling = tmp.newFolder("public")
        val sneaky = Uri.parse("file://${sibling.path}/../data/org.example.app/files/key")
        assertTrue(SharedUriGuard.pointsIntoAppStorage(sneaky, roots))
    }

    @Test
    fun `the app root itself is rejected`() {
        assertTrue(SharedUriGuard.pointsIntoAppStorage(fileUri(appRoot.path), roots))
    }

    @Test
    fun `file uri outside app storage is allowed`() {
        val publicFile = File(tmp.newFolder("Download"), "report.pdf")
        assertFalse(SharedUriGuard.pointsIntoAppStorage(fileUri(publicFile.path), roots))
    }

    @Test
    fun `a sibling dir sharing the root name prefix is not app storage`() {
        val lookalike = File(tmp.newFolder("data", "org.example.app2"), "file.txt")
        assertFalse(SharedUriGuard.pointsIntoAppStorage(fileUri(lookalike.path), roots))
    }

    @Test
    fun `content uris are left to the provider`() {
        val uri = Uri.parse("content://com.example.provider/files/report.pdf")
        assertFalse(SharedUriGuard.pointsIntoAppStorage(uri, roots))
    }

    @Test
    fun `file uri without a path is rejected`() {
        assertTrue(SharedUriGuard.pointsIntoAppStorage(Uri.parse("file:"), roots))
    }

    @Test
    fun `app storage roots include the private data dir`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val roots = SharedUriGuard.appStorageRoots(context)
        assertTrue(roots.any { it.canonicalPath == context.dataDir.canonicalPath })
        assertTrue(SharedUriGuard.pointsIntoAppStorage(fileUri(File(context.filesDir, "x").path), roots))
    }
}
