package com.difft.android.chat.attachment.migration

import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.attachment.AttachmentPathResolver
import difft.android.messageserialization.model.Attachment
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.synthesizedLocalId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The lazy half of the migration — the path the download job takes before it reaches the network,
 * and the resolver seam it registers.
 *
 * Reaches no database: both entry points work from the identifiers their caller already holds, which
 * is what lets the row-lock and copy behaviour be pinned on the JVM (the native WCDB library is not
 * available there, so the paged stages are verified on device).
 */
class ForwardAttachmentMigrationLazyPathTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var migration: ForwardAttachmentMigration

    @Before
    fun setUp() {
        mockkObject(FileUtil)
        every { FileUtil.getMessageAttachmentFilePath(any()) } answers {
            File(temp.root, firstArg<String>()).path + File.separator
        }
        migration = ForwardAttachmentMigration(FakePreferencesDataStore())
    }

    @After
    fun tearDown() {
        AttachmentPathResolver.migrator = null
        unmockkAll()
    }

    private fun legacyFile(key: String, name: String, content: String): File =
        File(File(temp.root, key), name).apply { parentFile.mkdirs(); writeText(content) }

    private fun currentDirectory(localId: String): File = File(temp.root, localId)

    private fun attachment(
        localId: String = "local-1",
        authorityId: Long = 42L,
        fileName: String? = "photo.jpg",
        isForwardCopy: Boolean = true
    ) = Attachment(
        id = "att-id",
        authorityId = authorityId,
        contentType = "image/jpeg",
        key = null,
        size = 5,
        thumbnail = null,
        digest = null,
        fileName = fileName,
        flags = 0,
        width = 1,
        height = 1,
        path = null,
        status = 2,
        localId = localId,
        isForwardCopy = isForwardCopy
    )

    private fun row(
        localId: String? = "local-1",
        authorityId: Long? = 42L,
        messageId: String? = "message-7",
        fileName: String? = "photo.jpg",
        size: Int = 5,
        isForward: Boolean = true
    ) = AttachmentModel().apply {
        this.localId = localId
        this.authorityId = authorityId
        this.messageId = messageId
        this.fileName = fileName
        this.size = size
        this.forwardModelDatabaseId = if (isForward) 1L else null
    }

    @Test
    fun `the seam brings a legacy file to the current address`() {
        legacyFile("42", "photo.jpg", "bytes")
        val target = currentDirectory("local-1")

        assertTrue(migration.migrateIfNeeded(attachment(), target.path, "message-7"))

        assertEquals("bytes", File(target, "photo.jpg").readText())
    }

    @Test
    fun `the seam brings a normal attachment across from its owner message directory`() {
        legacyFile("message-7", "photo.jpg", "bytes")
        val target = currentDirectory("local-1")

        assertTrue(migration.migrateIfNeeded(attachment(isForwardCopy = false), target.path, "message-7"))

        assertEquals("bytes", File(target, "photo.jpg").readText())
    }

    @Test
    fun `a normal attachment is never served from the authorityId directory`() {
        // That address belongs to forwarded copies; reading it here would reach into a sibling's file.
        legacyFile("42", "photo.jpg", "bytes")
        val target = currentDirectory("local-1")

        assertFalse(migration.migrateIfNeeded(attachment(isForwardCopy = false), target.path, "message-7"))
        assertFalse(File(target, "photo.jpg").exists())
    }

    @Test
    fun `the seam does nothing without any legacy address to look at`() {
        legacyFile("42", "photo.jpg", "bytes")
        val target = currentDirectory("local-1")

        assertFalse(migration.migrateIfNeeded(attachment(isForwardCopy = false), target.path, null))
        assertFalse(File(target, "photo.jpg").exists())
    }

    @Test
    fun `the seam reports a file that is already at the current address`() {
        val target = currentDirectory("local-1")
        File(target, "photo.jpg").apply { parentFile.mkdirs(); writeText("already here") }

        assertTrue(migration.migrateIfNeeded(attachment(), target.path, "message-7"))
    }

    @Test
    fun `a download is short-circuited only by a copy this call made`() {
        legacyFile("42", "photo.jpg", "bytes")
        val target = currentDirectory("local-1")

        assertTrue(migration.materializeFromLegacyAddress(row(), "local-1", File(target, "photo.jpg").path))
        assertEquals("bytes", File(target, "photo.jpg").readText())

        // Second time the file is already there: nothing was migrated, so the caller keeps its own
        // decision about whether to download.
        assertFalse(migration.materializeFromLegacyAddress(row(), "local-1", File(target, "photo.jpg").path))
    }

    @Test
    fun `a copy whose length contradicts the row is not reported as downloaded`() {
        legacyFile("42", "photo.jpg", "far longer than the row says")
        val target = currentDirectory("local-1")

        assertFalse(migration.materializeFromLegacyAddress(row(size = 5), "local-1", File(target, "photo.jpg").path))
    }

    @Test
    fun `the owner message directory is the second place looked at`() {
        legacyFile("message-7", "photo.jpg", "bytes")
        val target = currentDirectory("local-1")

        assertTrue(migration.materializeFromLegacyAddress(row(), "local-1", File(target, "photo.jpg").path))
        assertEquals("bytes", File(target, "photo.jpg").readText())
    }

    @Test
    fun `a normal row is rescued from its owner message directory`() {
        legacyFile("message-7", "photo.jpg", "bytes")
        val target = currentDirectory("local-1")

        assertTrue(migration.materializeFromLegacyAddress(row(isForward = false), "local-1", File(target, "photo.jpg").path))
        assertEquals("bytes", File(target, "photo.jpg").readText())
    }

    @Test
    fun `a normal row never reaches the authorityId directory of a forward sibling`() {
        legacyFile("42", "photo.jpg", "bytes")
        val target = currentDirectory("local-1")

        assertFalse(migration.materializeFromLegacyAddress(row(isForward = false), "local-1", File(target, "photo.jpg").path))
        assertFalse(File(target, "photo.jpg").exists())
    }

    @Test
    fun `a row the backfill has not reached is rescued under its synthesized identity`() {
        // The rows whose file is still at a legacy address are exactly the ones the backfill has not
        // stamped yet, so a NULL localId column must not end the rescue: the caller addresses such a
        // row by the id every reader synthesizes for it, and that is the key the rescue works under.
        val unbackfilled = row(localId = null)
        val rowKey = unbackfilled.synthesizedLocalId()
        legacyFile("message-7", "photo.jpg", "bytes")
        val target = currentDirectory(rowKey)

        assertTrue(migration.materializeFromLegacyAddress(unbackfilled, rowKey, File(target, "photo.jpg").path))
        assertEquals("bytes", File(target, "photo.jpg").readText())
        assertEquals("row locks must not accumulate", 0, migration.activeRowLocks())
    }

    @Test
    fun `a caller that can name no identity at all is refused`() {
        legacyFile("42", "photo.jpg", "bytes")

        assertFalse(
            migration.materializeFromLegacyAddress(row(localId = null), "", File(currentDirectory("local-1"), "photo.jpg").path)
        )
        assertFalse(File(currentDirectory("local-1"), "photo.jpg").exists())
    }

    @Test
    fun `a copy drops the stale validity cached for its address`() {
        legacyFile("42", "photo.jpg", "bytes")
        val target = currentDirectory("local-1")

        migration.migrateIfNeeded(attachment(), target.path, "message-7")

        verify { FileUtil.invalidateFileValidity(File(target, "photo.jpg").path) }
    }

    @Test
    fun `concurrent callers for one copy leave one intact file and no lock behind`() {
        legacyFile("42", "photo.jpg", "bytes")
        val target = currentDirectory("local-1")
        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            pool.execute {
                start.await()
                try {
                    migration.migrateIfNeeded(attachment(), target.path, "message-7")
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals("bytes", File(target, "photo.jpg").readText())
        assertEquals(1, target.listFiles()!!.size)
        assertEquals("row locks must not accumulate", 0, migration.activeRowLocks())
    }

    @Test
    fun `different copies do not share a lock entry after they finish`() {
        legacyFile("42", "photo.jpg", "bytes")

        migration.migrateIfNeeded(attachment(localId = "local-1"), currentDirectory("local-1").path, "message-7")
        migration.migrateIfNeeded(attachment(localId = "local-2"), currentDirectory("local-2").path, "message-7")

        assertEquals(0, migration.activeRowLocks())
        assertTrue(File(currentDirectory("local-1"), "photo.jpg").exists())
        assertTrue(File(currentDirectory("local-2"), "photo.jpg").exists())
    }
}
