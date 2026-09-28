package com.difft.android.chat.attachment

import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.attachment.migration.LegacyAttachmentFiles
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.ForwardSourceFallback
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The forward-send local copy: a forwarded attachment the user already has must land in the new
 * copy's own directory and be marked SUCCESS, and every failure mode must degrade to LOADING rather
 * than propagate.
 *
 * Real files in a temp folder — the whole point of the step is filesystem behaviour.
 */
class ForwardAttachmentMaterializerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = temp.newFolder("attachment")
        mockkObject(FileUtil)
        every { FileUtil.getMessageAttachmentFilePath(any()) } answers { File(root, firstArg<String>()).path + File.separator }
        every { FileUtil.isFileValid(any()) } answers { File(firstArg<String>()).let { it.isFile && it.length() > 0 } }
        every { FileUtil.invalidateFileValidity(any()) } returns Unit
    }

    @After
    fun tearDown() {
        // Registered globally by the migration module on a device; never leak one test's stand-in.
        AttachmentPathResolver.migrator = null
        unmockkAll()
    }

    /** A structurally valid ciphertext: `[IV16][16*n][HMAC32]`. */
    private fun writeCiphertext(file: File, blocks: Int = 2) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(16 + 16 * blocks + 32) { 7 })
    }

    private fun attachment(localId: String, sourceBasePath: String?) = Attachment(
        id = "server-id",
        authorityId = 777L,
        contentType = "image/jpeg",
        key = byteArrayOf(1),
        size = 10,
        thumbnail = null,
        digest = null,
        fileName = "photo.jpg",
        flags = 0,
        width = 1,
        height = 1,
        path = null,
        status = AttachmentStatus.LOADING.code,
        localId = localId,
        isForwardCopy = true
    ).also { it.forwardSourceFilePath = sourceBasePath }

    private fun contextOf(vararg attachments: Attachment) =
        ForwardContext(listOf(Forward(1L, 0, false, "author", null, attachments.toList(), null, null)), false)

    private fun sourceBasePath(dir: String) = File(File(root, dir), "photo.jpg").path

    private fun targetBasePath(localId: String) = File(File(root, localId), "photo.jpg").path

    @Test
    fun `ciphertext source is copied into the copy's own directory and marked SUCCESS`() {
        val source = sourceBasePath("msg-1")
        writeCiphertext(File("$source.encrypt"))
        val copy = attachment("local-new", source)

        val result = ForwardAttachmentMaterializer.materialize(contextOf(copy))

        assertEquals(1, result.copied)
        assertTrue(File(targetBasePath("local-new") + ".encrypt").isFile)
        assertEquals(AttachmentStatus.SUCCESS.code, copy.status)
    }

    @Test
    fun `legacy plaintext source is copied too`() {
        val source = sourceBasePath("msg-1")
        File(source).apply { parentFile?.mkdirs() }.writeText("hello")
        val copy = attachment("local-new", source)

        val result = ForwardAttachmentMaterializer.materialize(contextOf(copy))

        assertEquals(1, result.copied)
        assertEquals("hello", File(targetBasePath("local-new")).readText())
        assertEquals(AttachmentStatus.SUCCESS.code, copy.status)
    }

    @Test
    fun `no captured source leaves the copy LOADING and copies nothing`() {
        val copy = attachment("local-new", sourceBasePath = null)

        val result = ForwardAttachmentMaterializer.materialize(contextOf(copy))

        assertEquals(0, result.copied)
        assertEquals(1, result.skipped)
        assertEquals(AttachmentStatus.LOADING.code, copy.status)
        assertFalse(File(root, "local-new").exists())
    }

    @Test
    fun `source that disappeared between capture and send degrades to LOADING`() {
        val copy = attachment("local-new", sourceBasePath("gone"))

        val result = ForwardAttachmentMaterializer.materialize(contextOf(copy))

        assertEquals(0, result.copied)
        assertEquals(AttachmentStatus.LOADING.code, copy.status)
    }

    @Test
    fun `each copy of the same source file gets its own directory`() {
        val source = sourceBasePath("msg-1")
        writeCiphertext(File("$source.encrypt"))
        val first = attachment("local-a", source)
        val second = attachment("local-b", source)

        ForwardAttachmentMaterializer.materialize(contextOf(first, second))

        assertTrue(File(targetBasePath("local-a") + ".encrypt").isFile)
        assertTrue(File(targetBasePath("local-b") + ".encrypt").isFile)
    }

    @Test
    fun `nested forward leaves are materialized too`() {
        val source = sourceBasePath("msg-1")
        writeCiphertext(File("$source.encrypt"))
        val nestedCopy = attachment("local-nested", source)
        val context = ForwardContext(
            listOf(
                Forward(
                    1L, 0, false, "author", null, null,
                    listOf(Forward(2L, 0, false, "author", null, listOf(nestedCopy), null, null)),
                    null
                )
            ),
            false
        )

        val result = ForwardAttachmentMaterializer.materialize(context)

        assertEquals(1, result.copied)
        assertEquals(AttachmentStatus.SUCCESS.code, nestedCopy.status)
    }

    // region the legacy-address fallback

    /**
     * A stand-in for the migration seam: brings the original's file from `attachment/<ownerId>/` to
     * the address the resolver reports, exactly as `migrateIfNeeded` does on a real device.
     */
    private class FakeMigrator(private val root: File) : AttachmentPathResolver.Migrator {
        var calls = 0

        override fun migrateIfNeeded(
            attachment: Attachment,
            targetDirectory: String,
            legacyOwnerMessageId: String?
        ): Boolean {
            calls++
            val fileName = attachment.fileName ?: return false
            val legacy = File(File(root, legacyOwnerMessageId ?: return false), "$fileName.encrypt")
            if (!legacy.isFile) return false
            val target = File(File(targetDirectory), "$fileName.encrypt")
            target.parentFile?.mkdirs()
            legacy.copyTo(target, overwrite = true)
            return true
        }
    }

    private fun original(localId: String) = Attachment(
        id = "server-id",
        authorityId = 777L,
        contentType = "image/jpeg",
        key = byteArrayOf(1),
        size = 10,
        thumbnail = null,
        digest = null,
        fileName = "photo.jpg",
        flags = 0,
        width = 1,
        height = 1,
        path = null,
        status = AttachmentStatus.SUCCESS.code,
        localId = localId,
        isForwardCopy = false
    )

    @Test
    fun `a copy whose capture found nothing is still rescued from the original's legacy address`() {
        // The capture runs on the main thread and may only LOOK at the current address; the original's
        // file is still where it was written before per-copy addressing. Without this the forward
        // silently degrades to a re-download for a file the user already has.
        writeCiphertext(File(sourceBasePath("msg-1") + ".encrypt"))
        val migrator = FakeMigrator(root)
        AttachmentPathResolver.migrator = migrator
        val copy = attachment("local-new", sourceBasePath = null).also {
            it.forwardSourceFallback = ForwardSourceFallback(original("local-orig"), "msg-1")
        }

        val result = ForwardAttachmentMaterializer.materialize(contextOf(copy))

        assertEquals(1, migrator.calls)
        assertEquals(1, result.copied)
        assertTrue(File(targetBasePath("local-new") + ".encrypt").isFile)
        assertEquals(AttachmentStatus.SUCCESS.code, copy.status)
    }

    @Test
    fun `a fallback that finds nothing anywhere still degrades to LOADING`() {
        val migrator = FakeMigrator(root)
        AttachmentPathResolver.migrator = migrator
        val copy = attachment("local-new", sourceBasePath = null).also {
            it.forwardSourceFallback = ForwardSourceFallback(original("local-orig"), "msg-gone")
        }

        val result = ForwardAttachmentMaterializer.materialize(contextOf(copy))

        assertEquals(1, migrator.calls)
        assertEquals(0, result.copied)
        assertEquals(1, result.skipped)
        assertEquals(AttachmentStatus.LOADING.code, copy.status)
    }

    @Test
    fun `a capture that found its source never reaches for the migration seam`() {
        val source = sourceBasePath("msg-1")
        writeCiphertext(File("$source.encrypt"))
        val migrator = FakeMigrator(root)
        AttachmentPathResolver.migrator = migrator
        val copy = attachment("local-new", source).also {
            it.forwardSourceFallback = ForwardSourceFallback(original("local-orig"), "msg-1")
        }

        val result = ForwardAttachmentMaterializer.materialize(contextOf(copy))

        assertEquals(0, migrator.calls)
        assertEquals(1, result.copied)
    }

    // endregion

    @Test
    fun `a copy that cannot land leaves nothing under the real name and no temp behind`() {
        // The copy goes through a temp name for exactly this reason: a partial file under the name
        // readers trust would be marked SUCCESS by this very flow and render broken forever, since
        // the forward path never re-downloads a copy it believes it already has.
        //
        // The failure is provoked at the final rename: a non-empty DIRECTORY sits at the target name,
        // so the copy into the temp succeeds and `renameTo` cannot replace it.
        val source = sourceBasePath("msg-1")
        writeCiphertext(File("$source.encrypt"))
        val target = File(targetBasePath("local-new") + ".encrypt")
        target.mkdirs()
        File(target, "occupied").writeText("in the way")
        val copy = attachment("local-new", source)

        val result = ForwardAttachmentMaterializer.materialize(contextOf(copy))

        assertEquals(1, result.failed)
        assertEquals(AttachmentStatus.LOADING.code, copy.status)
        assertTrue("nothing may be written under the trusted name", target.isDirectory)
        assertTrue(
            "the failed copy's temp must not be left behind",
            File(root, "local-new").listFiles()!!.none { it.name.endsWith(LegacyAttachmentFiles.TEMP_SUFFIX) }
        )
    }

    @Test
    fun `a successful copy leaves no temp file behind`() {
        val source = sourceBasePath("msg-1")
        writeCiphertext(File("$source.encrypt"))
        File(source).writeText("hello")
        val copy = attachment("local-new", source)

        val result = ForwardAttachmentMaterializer.materialize(contextOf(copy))

        assertEquals(1, result.copied)
        assertTrue(
            File(root, "local-new").listFiles()!!.none { it.name.endsWith(LegacyAttachmentFiles.TEMP_SUFFIX) }
        )
    }

    @Test
    fun `a file already at the target address is not recopied but is still marked SUCCESS`() {
        val source = sourceBasePath("msg-1")
        writeCiphertext(File("$source.encrypt"))
        writeCiphertext(File(targetBasePath("local-new") + ".encrypt"), blocks = 5)
        val copy = attachment("local-new", source)

        val result = ForwardAttachmentMaterializer.materialize(contextOf(copy))

        assertEquals(0, result.copied)
        assertEquals(AttachmentStatus.SUCCESS.code, copy.status)
        // Untouched: still the pre-existing 5-block file.
        assertEquals(16L + 16 * 5 + 32, File(targetBasePath("local-new") + ".encrypt").length())
    }
}
