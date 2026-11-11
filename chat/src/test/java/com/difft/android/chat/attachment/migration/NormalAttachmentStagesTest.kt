package com.difft.android.chat.attachment.migration

import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.attachment.migration.NormalAttachmentStages.Companion.isSolelyOwnedLegacyDirectory
import com.difft.android.chat.attachment.migration.NormalAttachmentStages.Companion.mayReclaimLegacyMessageDirectory
import com.difft.android.chat.attachment.migration.NormalAttachmentStages.Companion.planFor
import com.difft.android.chat.attachment.migration.NormalAttachmentStages.Companion.renameDirectory
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.difft.app.database.models.AttachmentModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File





class NormalAttachmentStagesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun setUp() {
        mockkObject(FileUtil)
        every { FileUtil.getMessageAttachmentFilePath(any()) } answers {
            File(temp.root, firstArg<String>()).path + File.separator
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun directory(key: String): File = File(temp.root, key)

    private fun file(key: String, name: String, content: String): File =
        File(directory(key), name).apply { parentFile.mkdirs(); writeText(content) }

    

    private fun rowCopy(key: String, fileName: String) =
        LegacyAttachmentFiles.RowCopy(directory(key), fileName, expectedSize = 0)

    private fun row(
        localId: String? = "local-1",
        messageId: String? = "message-7",
        fileName: String? = "photo.jpg",
        authorityId: Long? = 42L
    ) = AttachmentModel().apply {
        this.localId = localId
        this.messageId = messageId
        this.fileName = fileName
        this.authorityId = authorityId
    }

    private fun counts(vararg entries: Pair<String, Int>) = mapOf(*entries)

    

    @Test
    fun `a directory holding only this row's file is renamed wholesale`() {
        file("message-7", "photo.jpg", "bytes")

        val plan = planFor(row(), counts("message-7" to 1))

        assertEquals(
            NormalRowPlan.Move("local-1", "photo.jpg", "message-7", directory("local-1").path + File.separator, true),
            plan
        )
    }

    @Test
    fun `the ciphertext sibling and a crash-orphaned temp still count as this row's own file`() {
        file("message-7", "photo.jpg.encrypt", "cipher")
        file("message-7", "photo.jpg${LegacyAttachmentFiles.TEMP_SUFFIX}", "half")

        assertTrue(isSolelyOwnedLegacyDirectory(directory("message-7"), "photo.jpg", referencingRowCount = 1))
    }

    @Test
    fun `a directory contaminated by a forward copy's legacy file is copied, never renamed`() {
        
        
        
        file("message-7", "photo.jpg", "bytes")
        file("message-7", "clip.mp4", "another row's only copy")

        val plan = planFor(row(), counts("message-7" to 1))

        assertEquals(false, (plan as NormalRowPlan.Move).wholeDirectory)
        assertFalse(isSolelyOwnedLegacyDirectory(directory("message-7"), "photo.jpg", referencingRowCount = 1))
    }

    @Test
    fun `a directory two rows name is copied, never renamed`() {
        file("message-7", "photo.jpg", "bytes")

        val plan = planFor(row(), counts("message-7" to 2))

        assertEquals(false, (plan as NormalRowPlan.Move).wholeDirectory)
    }

    @Test
    fun `a row nothing counts is copied, never renamed`() {
        
        file("message-7", "photo.jpg", "bytes")

        val plan = planFor(row(), counts())

        assertEquals(false, (plan as NormalRowPlan.Move).wholeDirectory)
    }

    @Test
    fun `a foreign file that merely shares the name prefix blocks the rename`() {
        
        
        
        file("message-7", "photo.jpg", "bytes")
        file("message-7", "photo.jpg.mp4", "another row's only copy")

        assertFalse(isSolelyOwnedLegacyDirectory(directory("message-7"), "photo.jpg", referencingRowCount = 1))
    }

    @Test
    fun `a nested directory or a missing listing is never renamed`() {
        File(directory("message-7"), "nested").mkdirs()
        assertFalse(isSolelyOwnedLegacyDirectory(directory("message-7"), "photo.jpg", referencingRowCount = 1))

        assertFalse(isSolelyOwnedLegacyDirectory(directory("absent"), "photo.jpg", referencingRowCount = 1))
    }

    @Test
    fun `an empty legacy directory is never renamed`() {
        directory("message-7").mkdirs()

        assertFalse(isSolelyOwnedLegacyDirectory(directory("message-7"), "photo.jpg", referencingRowCount = 1))
    }

    

    

    @Test
    fun `a send that never finished uploading is copied, never renamed out from under the resend`() {
        
        
        
        
        
        file("message-7", "photo.jpg", "bytes")

        assertEquals(false, (planFor(row(authorityId = 0L), counts("message-7" to 1)) as NormalRowPlan.Move).wholeDirectory)
        assertEquals(false, (planFor(row(authorityId = null), counts("message-7" to 1)) as NormalRowPlan.Move).wholeDirectory)
        
        assertTrue((planFor(row(authorityId = 42L), counts("message-7" to 1)) as NormalRowPlan.Move).wholeDirectory)
    }

    @Test
    fun `a dead never-uploaded row with no staged file settles instead of holding the stage forever`() {
        
        
        
        val plan = planFor(row(authorityId = 0L), counts("message-7" to 1))

        assertTrue(plan is NormalRowPlan.Move)
    }

    @Test
    fun `a row already at its own address is only offered for status repair`() {
        file("local-1", "photo.jpg", "bytes")
        file("message-7", "photo.jpg", "bytes")

        val plan = planFor(row(), counts("message-7" to 1))

        assertEquals(NormalRowPlan.AlreadyMigrated(File(directory("local-1"), "photo.jpg")), plan)
    }

    @Test
    fun `a never-uploaded row already at its own address is settled, with nothing left to copy`() {
        
        
        file("local-1", "photo.jpg", "bytes")

        assertTrue(planFor(row(authorityId = 0L), counts()) is NormalRowPlan.AlreadyMigrated)
    }

    @Test
    fun `a row that names no directory, no file or no owner is skipped`() {
        assertEquals(NormalRowPlan.Skip, planFor(row(localId = null), counts()))
        assertEquals(NormalRowPlan.Skip, planFor(row(localId = ""), counts()))
        assertEquals(NormalRowPlan.Skip, planFor(row(fileName = null), counts()))
        assertEquals(NormalRowPlan.Skip, planFor(row(messageId = null), counts()))
        assertEquals(NormalRowPlan.Skip, planFor(row(messageId = ""), counts()))
    }

    @Test
    fun `a row whose local id is already its directory has nothing to move`() {
        assertEquals(NormalRowPlan.Skip, planFor(row(localId = "message-7"), counts("message-7" to 1)))
    }

    

    

    @Test
    fun `the rename moves the whole directory and drops the validity cached for it`() {
        file("message-7", "photo.jpg", "bytes")
        file("message-7", "photo.jpg.encrypt", "cipher")

        assertTrue(renameDirectory(directory("message-7"), directory("local-1")))

        assertFalse(directory("message-7").exists())
        assertEquals("bytes", File(directory("local-1"), "photo.jpg").readText())
        assertEquals("cipher", File(directory("local-1"), "photo.jpg.encrypt").readText())
        verify { FileUtil.invalidateFileValidityUnder(directory("message-7").path) }
    }

    @Test
    fun `re-running after a completed rename is a no-op, not a second move`() {
        file("message-7", "photo.jpg", "bytes")
        assertTrue(renameDirectory(directory("message-7"), directory("local-1")))

        
        
        assertFalse(renameDirectory(directory("message-7"), directory("local-1")))
        assertEquals(
            NormalRowPlan.AlreadyMigrated(File(directory("local-1"), "photo.jpg")),
            planFor(row(), counts("message-7" to 1))
        )
        assertEquals("bytes", File(directory("local-1"), "photo.jpg").readText())
    }

    @Test
    fun `the rename refuses a target that already holds something`() {
        
        
        file("message-7", "photo.jpg", "legacy bytes")
        file("local-1", "photo.jpg", "freshly downloaded")

        assertFalse(renameDirectory(directory("message-7"), directory("local-1")))

        assertEquals("freshly downloaded", File(directory("local-1"), "photo.jpg").readText())
        assertEquals("legacy bytes", File(directory("message-7"), "photo.jpg").readText())
    }

    @Test
    fun `renaming a directory that is not there reports failure without creating one`() {
        assertFalse(renameDirectory(directory("message-7"), directory("local-1")))

        assertFalse(directory("local-1").exists())
    }

    

    

    @Test
    fun `a legacy directory whose every file is verified elsewhere is reclaimed`() {
        
        
        file("message-7", "photo.jpg", "bytes")
        file("local-1", "photo.jpg", "bytes")

        assertTrue(mayReclaimLegacyMessageDirectory(directory("message-7"), listOf(rowCopy("local-1", "photo.jpg"))))
    }

    @Test
    fun `a legacy file with no counterpart keeps the whole directory`() {
        
        
        file("message-7", "photo.jpg", "bytes")
        file("message-7", "clip.mp4", "another row's only copy")
        file("local-1", "photo.jpg", "bytes")

        assertFalse(mayReclaimLegacyMessageDirectory(directory("message-7"), listOf(rowCopy("local-1", "photo.jpg"))))
    }

    @Test
    fun `a counterpart of a different length is not a counterpart`() {
        file("message-7", "photo.jpg", "the complete bytes")
        file("local-1", "photo.jpg", "truncated")

        assertFalse(mayReclaimLegacyMessageDirectory(directory("message-7"), listOf(rowCopy("local-1", "photo.jpg"))))
    }

    @Test
    fun `a directory no row accounts for is never swept`() {
        
        
        file("message-7", "photo.jpg", "bytes")

        assertFalse(mayReclaimLegacyMessageDirectory(directory("message-7"), emptyList()))
    }

    @Test
    fun `verification spans every referencing row, not just the first`() {
        
        file("message-7", "photo.jpg", "bytes")
        file("message-7", "clip.mp4", "forwarded bytes")
        file("local-1", "photo.jpg", "bytes")
        file("local-2", "clip.mp4", "forwarded bytes")

        assertTrue(
            mayReclaimLegacyMessageDirectory(
                directory("message-7"),
                listOf(rowCopy("local-1", "photo.jpg"), rowCopy("local-2", "clip.mp4"))
            )
        )
    }

    @Test
    fun `a directory that is not there, or holds a nested directory, is never swept`() {
        assertFalse(mayReclaimLegacyMessageDirectory(directory("absent"), listOf(rowCopy("local-1", "photo.jpg"))))

        File(directory("message-7"), "nested").mkdirs()
        assertFalse(mayReclaimLegacyMessageDirectory(directory("message-7"), listOf(rowCopy("local-1", "photo.jpg"))))
    }

    
}
