package com.difft.android.app.startup

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Environment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class StartupExifTempSweepTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun coldStartEntrySweepsExifCopiesAndAvatarCacheWithoutDeletingOtherFiles() {
        val cache = temporaryFolder.newFolder("cache")
        val externalCache = temporaryFolder.newFolder("external-cache")
        val pictures = temporaryFolder.newFolder("Pictures")
        val context: Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getCacheDir(): File = cache
            override fun getExternalCacheDir(): File = externalCache
            override fun getExternalFilesDir(type: String?): File? = if (type == Environment.DIRECTORY_PICTURES) pictures else null
        }
        
        val generated = File.createTempFile("temp", "tmp", cache)
        
        val unrelated = listOf(
            file(cache, "temp123.tmp"), file(cache, "other.tmp"), file(cache, "temp123.tmp.bak"),
            file(cache, "attempt123tmp"), file(cache, "template.txt")
        )
        val directory = File(cache, "temp-directory.tmp").apply { mkdirs() }
        val nested = file(directory, "temp456.tmp")
        val compressed = file(externalCache, "avatar_compress/avatar.jpg")

        sweepAvatarCropTemp(context)

        assertFalse(generated.exists())
        assertFalse(compressed.exists())
        unrelated.forEach { assertTrue(it.exists()) }
        assertTrue(directory.isDirectory)
        assertTrue(nested.exists())
    }

    private fun file(root: File, name: String): File = File(root, name).apply {
        parentFile!!.mkdirs()
        writeBytes(byteArrayOf(1, 2, 3))
    }
}
