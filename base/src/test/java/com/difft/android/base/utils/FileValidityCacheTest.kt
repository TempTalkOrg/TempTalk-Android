package com.difft.android.base.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [FileUtil.isFileValid] caches only positive results, keyed by exact path, and is otherwise cleared
 * only on logout — so a stale `true` outlives any file that is deleted or moved. This pins the
 * invalidation API that deletion and the attachment migration rely on.
 */
class FileValidityCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a deleted file still reads as valid until its cache entry is invalidated`() {
        val file = temp.newFile("photo.jpg").apply { writeText("bytes") }

        assertTrue(FileUtil.isFileValid(file.path))
        file.delete()
        // The stale positive is the behaviour that makes the API necessary.
        assertTrue(FileUtil.isFileValid(file.path))

        FileUtil.invalidateFileValidity(file.path)
        assertFalse(FileUtil.isFileValid(file.path))
    }

    @Test
    fun `invalidating a base path also drops its ciphertext sibling`() {
        val base = temp.newFile("media.bin").apply { writeText("bytes") }
        val encrypted = File(base.path + ".encrypt").apply { writeText("cipher") }

        assertTrue(FileUtil.isFileValid(base.path))
        assertTrue(FileUtil.isFileValid(encrypted.path))
        base.delete()
        encrypted.delete()

        FileUtil.invalidateFileValidity(base.path)

        assertFalse(FileUtil.isFileValid(base.path))
        assertFalse(FileUtil.isFileValid(encrypted.path))
    }

    @Test
    fun `invalidating a directory drops every cached path under it`() {
        val dir = temp.newFolder("attachment-dir")
        val first = File(dir, "a.jpg").apply { writeText("a") }
        val second = File(dir, "b.jpg").apply { writeText("b") }
        val outside = temp.newFile("outside.jpg").apply { writeText("c") }

        assertTrue(FileUtil.isFileValid(first.path))
        assertTrue(FileUtil.isFileValid(second.path))
        assertTrue(FileUtil.isFileValid(outside.path))
        first.delete()
        second.delete()
        outside.delete()

        FileUtil.invalidateFileValidityUnder(dir.path)

        assertFalse(FileUtil.isFileValid(first.path))
        assertFalse(FileUtil.isFileValid(second.path))
        // Untouched: still cached positive, proving the sweep is scoped to the directory.
        assertTrue(FileUtil.isFileValid(outside.path))
    }
}
