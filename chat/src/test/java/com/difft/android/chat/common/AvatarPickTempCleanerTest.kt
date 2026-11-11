package com.difft.android.chat.common

import android.content.Context
import android.content.ContextWrapper
import android.os.Environment
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.test.builders.LocalMediaBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class AvatarPickTempCleanerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var cache: File
    private lateinit var pictures: File

    @Before
    fun setUp() {
        cache = temporaryFolder.newFolder("Android", "data", "avatar-test", "cache")
        pictures = temporaryFolder.newFolder("Pictures")
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getExternalCacheDir(): File = cache
            override fun getExternalFilesDir(type: String?): File? = if (type == Environment.DIRECTORY_PICTURES) pictures else null
        }
    }

    @Test
    fun uploadCleanupIncludesAvatarCacheAndProtectsOtherFiles() {
        val compressed = file(cache, "avatar_compress/avatar.jpg")
        val legacy = file(cache, "luban_disk_cache/avatar.jpg")
        val neighbor = file(cache, "avatar_compress_other/gallery.jpg")
        val gallery = file(temporaryFolder.root, "gallery/original.jpg")

        listOf(compressed, legacy, neighbor, gallery).forEach { AvatarPickTempCleaner.deleteUploadedTemp(context, it.path) }

        assertFalse(compressed.exists())
        assertFalse(legacy.exists())
        assertTrue(neighbor.exists())
        assertTrue(gallery.exists())
    }

    @Test
    fun sweepClearsNewAndLegacyCachesAndOnlyCropFilesInPictures() {
        val compressed = file(cache, "avatar_compress/avatar.jpg")
        val legacy = file(cache, "luban_disk_cache/avatar.jpg")
        val crop = file(pictures, "CROP_avatar.jpg")
        val download = file(pictures, "download.jpg")
        val nested = file(cache, "avatar_compress/nested/keep.jpg")
        val unrelated = file(cache, "other/avatar.jpg")

        AvatarPickTempCleaner.sweepPickTempDirs(context)

        listOf(compressed, legacy, crop).forEach { assertFalse(it.exists()) }
        listOf(download, nested, unrelated).forEach { assertTrue(it.exists()) }
    }

    @Test
    fun sourceReturnedAfterFailureSurvivesUntilUploadCompletes() {
        val crop = file(pictures, "CROP_avatar.jpg")
        val gallery = file(temporaryFolder.root, "gallery/original.jpg")
        val media = LocalMediaBuilder.gallery(realPath = gallery.path).apply {
            cutPath = crop.path
            sandboxPath = crop.path
        }

        AvatarPickTempCleaner.deleteCropTemp(media, keepPath = crop.path)
        assertTrue(crop.exists())
        assertTrue(gallery.exists())

        AvatarPickTempCleaner.deleteUploadedTemp(context, crop.path)
        assertFalse(crop.exists())
        assertTrue(gallery.exists())
    }

    @Test
    fun successfulCompressionKeepsOutputWhileDeletingCrop() {
        val crop = file(pictures, "CROP_avatar.jpg")
        val compressed = file(cache, "avatar_compress/avatar.jpg")
        val media = LocalMediaBuilder.sandbox().apply {
            cutPath = crop.path
            sandboxPath = crop.path
        }

        AvatarPickTempCleaner.deleteCropTemp(media, keepPath = compressed.path)

        assertFalse(crop.exists())
        assertTrue(compressed.exists())
    }

    private fun file(root: File, name: String): File = File(root, name).apply {
        parentFile!!.mkdirs()
        writeBytes(byteArrayOf(1, 2, 3))
    }
}
