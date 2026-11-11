package com.difft.android.base.utils

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File


class ShareImportQuotaTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun File.staged(name: String, bytes: Int): File = File(this, name).also { it.writeBytes(ByteArray(bytes)) }

    @Test
    fun `an empty staging dir has the whole quota`() {
        val dir = tempFolder.newFolder("share_import")
        assertEquals(1_000L, FileUtil.remainingStagingBytes(dir, quota = 1_000L))
    }

    @Test
    fun `staged files use up the quota`() {
        val dir = tempFolder.newFolder("share_import")
        dir.staged("a.jpg", 300)
        dir.staged("b.pdf", 500)
        assertEquals(200L, FileUtil.remainingStagingBytes(dir, quota = 1_000L))
    }

    @Test
    fun `going over the quota reports a negative remainder`() {
        val dir = tempFolder.newFolder("share_import")
        dir.staged("a.bin", 1_200)
        assertEquals(-200L, FileUtil.remainingStagingBytes(dir, quota = 1_000L))
    }

    @Test
    fun `a missing staging dir has the whole quota`() {
        val dir = File(tempFolder.root, "never_created")
        assertEquals(1_000L, FileUtil.remainingStagingBytes(dir, quota = 1_000L))
    }

    @Test
    fun `the default quota fits two max-size shares`() {
        assertEquals(2L * FileUtil.MAX_SUPPORT_FILE_SIZE, FileUtil.SHARE_IMPORT_QUOTA_BYTES)
    }
}
