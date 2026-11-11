package com.difft.android.base.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
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

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun registerProvider(authority: String, owner: String) {
        val provider = ProviderInfo().apply {
            this.authority = authority
            packageName = owner
            name = "com.example.SomeProvider"
        }
        shadowOf(context.packageManager).addOrUpdateProvider(provider)
    }

    @Test
    fun `content uri served by the app's own provider is rejected`() {
        registerProvider("${context.packageName}.provider", context.packageName)
        val uri = Uri.parse("content://${context.packageName}.provider/files/key")
        assertTrue(SharedUriGuard.isOwnProviderUri(context, uri))
    }

    @Test
    fun `user id prefix does not hide the app's own provider`() {
        registerProvider("${context.packageName}.encryptedattachment", context.packageName)
        val uri = Uri.parse("content://0@${context.packageName}.encryptedattachment/a/b")
        assertTrue(SharedUriGuard.isOwnProviderUri(context, uri))
    }

    @Test
    fun `own provider with an unprefixed authority is rejected`() {
        registerProvider("legacy.files", context.packageName)
        assertTrue(SharedUriGuard.isOwnProviderUri(context, Uri.parse("content://legacy.files/x")))
    }

    @Test
    fun `other apps' content uris are allowed`() {
        assertFalse(SharedUriGuard.isOwnProviderUri(context, Uri.parse("content://com.example.provider/x")))
    }

    @Test
    fun `a separate install whose package extends ours is not this app`() {
        
        val sibling = "${context.packageName}.test"
        registerProvider("$sibling.provider", sibling)
        assertFalse(SharedUriGuard.isOwnProviderUri(context, Uri.parse("content://$sibling.provider/files/a.pdf")))
    }

    @Test
    fun `file uris are not provider uris`() {
        assertFalse(SharedUriGuard.isOwnProviderUri(context, fileUri("/sdcard/Download/a.pdf")))
    }

    @Test
    fun `chooser excludes only the app's own targets`() {
        val target = Intent(Intent.ACTION_SEND).setType("image/png")
        val pm = shadowOf(context.packageManager)
        pm.addResolveInfoForIntent(target, resolveInfo(context.packageName, "com.example.MainActivity"))
        pm.addResolveInfoForIntent(target, resolveInfo("com.other.app", "com.other.ShareActivity"))

        val chooser = SharedUriGuard.excludeOwnTargets(context, Intent.createChooser(target, null))

        val excluded = IntentCompat.getParcelableArrayExtra(chooser, Intent.EXTRA_EXCLUDE_COMPONENTS, ComponentName::class.java)
        assertEquals(listOf(ComponentName(context.packageName, "com.example.MainActivity")), excluded?.toList())
    }

    private fun resolveInfo(pkg: String, activity: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = activity
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
    }
}
