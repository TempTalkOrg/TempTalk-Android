package com.difft.android.selector.entity

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class LocalMediaCopyTest {

    @Test
    fun `copy preserves every instance field without invoking equality`() {
        val source = LocalMedia(
            id = 11,
            path = "content://media/external/images/media/11",
            realPath = "/gallery/photo.png",
            originalPath = "/original/photo.png",
            compressPath = "/compressed/photo.jpg",
            cutPath = "/crop/photo.jpg",
            watermarkPath = "/watermark/photo.jpg",
            sandboxPath = "/sandbox/photo.jpg",
            duration = 12,
            isChecked = true,
            cutFlag = true,
            position = 13,
            num = 14,
            mimeType = "image/png",
            chooseModel = 15,
            isCameraSource = true,
            compressed = true,
            width = 1600,
            height = 1200,
            cropImageWidth = 800,
            cropImageHeight = 600,
            cropOffsetX = 16,
            cropOffsetY = 17,
            cropResultAspectRatio = 1.25f,
            size = 180_000,
            originalFlag = true,
            fileName = "photo.png",
            parentFolderName = "gallery",
            bucketId = 19,
            dateAddedTime = 20,
            customData = "custom",
            isMaxSelectEnabledMask = true,
            isGalleryEnabledMask = true,
            editorImageFlag = true,
        ).apply { compareLocalMedia = LocalMedia(id = 21) }

        val copy = source.copyForTransform()

        assertNotSame(source, copy)
        
        LocalMedia::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { field ->
            field.isAccessible = true
            assertEquals(field.name, field.get(source), field.get(copy))
        }
        assertSame(source.compareLocalMedia, copy.compareLocalMedia)
        copy.realPath = "/output/photo.jpg"
        copy.mimeType = "image/jpeg"
        copy.fileName = "photo.jpg"
        copy.width = 640
        assertEquals("/gallery/photo.png", source.realPath)
        assertEquals("image/png", source.mimeType)
        assertEquals("photo.png", source.fileName)
        assertEquals(1600, source.width)
    }

    @Test
    fun `copy retains raw flags even when paths hide their effective values`() {
        val source = LocalMedia(cutFlag = true, originalFlag = true, editorImageFlag = true, compressed = true)
        assertFalse(source.isCut)
        assertFalse(source.isOriginal)
        assertFalse(source.isEditorImage)
        assertFalse(source.isCompressed)

        val copy = source.copyForTransform().apply {
            cutPath = "/crop/photo.jpg"
            originalPath = "/original/photo.png"
            compressPath = "/compressed/photo.jpg"
        }

        assertTrue(copy.isCut)
        assertTrue(copy.isOriginal)
        assertTrue(copy.isEditorImage)
        assertTrue(copy.isCompressed)
        assertFalse(source.isCut)
        assertFalse(source.isOriginal)
        assertFalse(source.isEditorImage)
        assertFalse(source.isCompressed)
    }
}
