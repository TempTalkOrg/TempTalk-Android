package com.difft.android.chat.attachment.migration

import com.difft.android.base.utils.FileUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The file half of the forward-attachment migration, against a real (temp) filesystem.
 *
 * These are the decisions that can destroy a user's only copy of a file — what gets copied, what
 * counts as a verified counterpart, and which legacy directory may be deleted — so they are pinned
 * here rather than only on a device: the native WCDB library is unavailable on the JVM, which is
 * exactly why [LegacyAttachmentFiles] takes directories instead of rows.
 *
 * Corpus mirrors the shapes a real install holds: plaintext-only, encrypted-at-rest, missing,
 * several rows sharing one legacy directory, already migrated, and an orphan nobody references.
 */
class LegacyAttachmentFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var legacy: File

    /** `[IV16][one 16-byte block][HMAC32]` — the shape `isStructurallyCompleteCiphertext` accepts. */
    private fun ciphertextBytes(seed: Byte): ByteArray = ByteArray(16 + 16 + 32) { (it + seed).toByte() }

    private fun plaintext(directory: File, name: String, content: String): File =
        File(directory, name).apply { parentFile.mkdirs(); writeText(content) }

    private fun ciphertext(directory: File, name: String, seed: Byte = 1): File =
        File(directory, "$name.encrypt").apply { parentFile.mkdirs(); writeBytes(ciphertextBytes(seed)) }

    private fun newDirectory(name: String): File = File(temp.root, name)

    private fun setUpLegacy(): File = temp.newFolder("42").also { legacy = it }

    private fun claim(directory: File, fileName: String, expectedSize: Int) =
        LegacyAttachmentFiles.RowCopy(directory, fileName, expectedSize)

    @Test
    fun `plaintext is copied to the current address and the legacy file stays`() {
        val source = plaintext(setUpLegacy(), "photo.jpg", "bytes")
        val target = newDirectory("local-1")

        val outcome = LegacyAttachmentFiles.materialize(listOf(legacy), target, "photo.jpg")

        assertEquals(LegacyAttachmentFiles.CopyOutcome.COPIED, outcome)
        assertEquals("bytes", File(target, "photo.jpg").readText())
        assertTrue("legacy file must survive — other copies still read it", source.exists())
    }

    @Test
    fun `ciphertext is copied as ciphertext`() {
        val source = ciphertext(setUpLegacy(), "photo.jpg")
        val target = newDirectory("local-1")

        val outcome = LegacyAttachmentFiles.materialize(listOf(legacy), target, "photo.jpg")

        assertEquals(LegacyAttachmentFiles.CopyOutcome.COPIED, outcome)
        assertArrayEquals(source.readBytes(), File(target, "photo.jpg.encrypt").readBytes())
        assertFalse("no plaintext is invented", File(target, "photo.jpg").exists())
    }

    @Test
    fun `a missing legacy file reports no source instead of failing`() {
        setUpLegacy()
        val target = newDirectory("local-1")

        assertEquals(
            LegacyAttachmentFiles.CopyOutcome.NO_SOURCE,
            LegacyAttachmentFiles.materialize(listOf(legacy), target, "photo.jpg")
        )
        assertFalse(File(target, "photo.jpg").exists())
    }

    @Test
    fun `a copy that cannot land reports FAILED, never no-source`() {
        // A readable source WAS found and the transfer still did not complete. Reporting NO_SOURCE
        // here counts as "skipped", which advances the migration watermark past a row whose bytes
        // never landed and lets the sweep delete the only copy left.
        //
        // The failure is provoked at the final rename: a non-empty DIRECTORY sits at the target
        // name, so the copy into the temp name succeeds and `renameTo` cannot replace it.
        plaintext(setUpLegacy(), "photo.jpg", "bytes")
        val target = newDirectory("local-1")
        File(target, "photo.jpg").mkdirs()
        File(target, "photo.jpg/occupied").writeText("in the way")

        val outcome = LegacyAttachmentFiles.materialize(listOf(legacy), target, "photo.jpg")

        assertEquals(LegacyAttachmentFiles.CopyOutcome.FAILED, outcome)
        assertTrue("the legacy file must survive a failed copy", File(legacy, "photo.jpg").exists())
    }

    @Test
    fun `a truncated legacy ciphertext is not treated as a source`() {
        setUpLegacy()
        File(legacy, "photo.jpg.encrypt").writeBytes(ByteArray(40))
        val target = newDirectory("local-1")

        assertEquals(
            LegacyAttachmentFiles.CopyOutcome.NO_SOURCE,
            LegacyAttachmentFiles.materialize(listOf(legacy), target, "photo.jpg")
        )
    }

    @Test
    fun `one legacy directory serves every copy that shares it`() {
        ciphertext(setUpLegacy(), "photo.jpg")
        val first = newDirectory("local-1")
        val second = newDirectory("local-2")

        assertEquals(
            LegacyAttachmentFiles.CopyOutcome.COPIED,
            LegacyAttachmentFiles.materialize(listOf(legacy), first, "photo.jpg")
        )
        assertEquals(
            LegacyAttachmentFiles.CopyOutcome.COPIED,
            LegacyAttachmentFiles.materialize(listOf(legacy), second, "photo.jpg")
        )
        assertTrue(File(first, "photo.jpg.encrypt").exists())
        assertTrue(File(second, "photo.jpg.encrypt").exists())
    }

    @Test
    fun `an already migrated copy is skipped, not overwritten`() {
        ciphertext(setUpLegacy(), "photo.jpg", seed = 1)
        val target = newDirectory("local-1")
        val existing = ciphertext(target, "photo.jpg", seed = 9)
        val existingBytes = existing.readBytes()

        assertEquals(
            LegacyAttachmentFiles.CopyOutcome.ALREADY_PRESENT,
            LegacyAttachmentFiles.materialize(listOf(legacy), target, "photo.jpg")
        )
        assertArrayEquals(existingBytes, existing.readBytes())
    }

    @Test
    fun `running twice leaves exactly the same result`() {
        plaintext(setUpLegacy(), "doc.pdf", "content")
        val target = newDirectory("local-1")

        val first = LegacyAttachmentFiles.materialize(listOf(legacy), target, "doc.pdf")
        val second = LegacyAttachmentFiles.materialize(listOf(legacy), target, "doc.pdf")

        assertEquals(LegacyAttachmentFiles.CopyOutcome.COPIED, first)
        assertEquals(LegacyAttachmentFiles.CopyOutcome.ALREADY_PRESENT, second)
        assertEquals("content", File(target, "doc.pdf").readText())
        assertEquals(1, target.listFiles()!!.size)
    }

    @Test
    fun `the second legacy address is tried when the first holds nothing`() {
        setUpLegacy()
        val ownerDirectory = temp.newFolder("message-7")
        plaintext(ownerDirectory, "voice.m4a", "audio")
        val target = newDirectory("local-1")

        assertEquals(
            LegacyAttachmentFiles.CopyOutcome.COPIED,
            LegacyAttachmentFiles.materialize(listOf(legacy, ownerDirectory), target, "voice.m4a")
        )
        assertEquals("audio", File(target, "voice.m4a").readText())
    }

    @Test
    fun `a copy is verified against the row size before it counts as downloaded`() {
        val target = newDirectory("local-1")
        plaintext(target, "photo.jpg", "12345")

        assertTrue(LegacyAttachmentFiles.isVerified(File(target, "photo.jpg"), expectedSize = 5))
        assertFalse(LegacyAttachmentFiles.isVerified(File(target, "photo.jpg"), expectedSize = 99))
        // A size the row never recorded cannot contradict the file.
        assertTrue(LegacyAttachmentFiles.isVerified(File(target, "photo.jpg"), expectedSize = 0))
    }

    @Test
    fun `a ciphertext is verified structurally, whatever the row size says`() {
        val target = newDirectory("local-1")
        ciphertext(target, "photo.jpg")

        assertTrue(LegacyAttachmentFiles.isVerified(File(target, "photo.jpg"), expectedSize = 12345))
    }

    @Test
    fun `a legacy directory is deletable only once every file has a counterpart`() {
        setUpLegacy()
        plaintext(legacy, "a.jpg", "aaa")
        plaintext(legacy, "b.jpg", "bbb")
        val first = newDirectory("local-1").also { plaintext(it, "a.jpg", "aaa") }

        assertFalse(
            "b.jpg is still only in the legacy directory",
            LegacyAttachmentFiles.isFullyMigrated(legacy, listOf(claim(first, "a.jpg", 3)))
        )

        val second = newDirectory("local-2").also { plaintext(it, "b.jpg", "bbb") }
        assertTrue(
            LegacyAttachmentFiles.isFullyMigrated(
                legacy,
                listOf(claim(first, "a.jpg", 3), claim(second, "b.jpg", 3))
            )
        )
    }

    @Test
    fun `a counterpart of a different length does not count`() {
        setUpLegacy()
        plaintext(legacy, "a.jpg", "aaa")
        val current = newDirectory("local-1").also { plaintext(it, "a.jpg", "truncated-differently") }

        assertFalse(LegacyAttachmentFiles.isFullyMigrated(legacy, listOf(claim(current, "a.jpg", 3))))
    }

    @Test
    fun `an orphan legacy directory is never deletable`() {
        setUpLegacy()
        plaintext(legacy, "a.jpg", "aaa")

        assertFalse(LegacyAttachmentFiles.isFullyMigrated(legacy, emptyList()))
        assertTrue(legacy.exists())
    }

    @Test
    fun `a sibling whose copy never landed blocks the deletion another sibling would authorize`() {
        // The defect a per-DIRECTORY proof has: both rows read the same legacy file, one migrated and
        // one did not, and the file-level check passes on the sibling that did.
        setUpLegacy()
        plaintext(legacy, "shared.jpg", "aaa")
        val landed = newDirectory("local-1").also { plaintext(it, "shared.jpg", "aaa") }
        val neverLanded = newDirectory("local-2")

        assertFalse(
            LegacyAttachmentFiles.isFullyMigrated(
                legacy,
                listOf(claim(landed, "shared.jpg", 3), claim(neverLanded, "shared.jpg", 3))
            )
        )

        plaintext(neverLanded, "shared.jpg", "aaa")
        assertTrue(
            LegacyAttachmentFiles.isFullyMigrated(
                legacy,
                listOf(claim(landed, "shared.jpg", 3), claim(neverLanded, "shared.jpg", 3))
            )
        )
    }

    @Test
    fun `a row whose file this directory never held cannot block the deletion`() {
        // A forward minted after the addressing flip is handed the same authorityId, so it ADDRESSES
        // this directory — but its bytes were only ever written under its own localId. Counting it as
        // a claimant lets a never-downloaded (or confidential) re-forward keep a reclaimable
        // directory alive until the sweep gives up.
        setUpLegacy()
        plaintext(legacy, "a.jpg", "aaa")
        val migrated = newDirectory("local-1").also { plaintext(it, "a.jpg", "aaa") }
        val postFlip = newDirectory("local-2")

        assertTrue(
            LegacyAttachmentFiles.isFullyMigrated(
                legacy,
                listOf(claim(migrated, "a.jpg", 3), claim(postFlip, "voice.m4a", 7))
            )
        )
    }

    @Test
    fun `an emptied directory is not held open by a row that never lived in it`() {
        // The single-reference move already carried the only file out. A post-flip row addressing the
        // same authorityId has nothing here to prove, downloaded or not.
        setUpLegacy()
        val postFlip = newDirectory("local-2")

        assertTrue(LegacyAttachmentFiles.isFullyMigrated(legacy, listOf(claim(postFlip, "a.jpg", 3))))
    }

    @Test
    fun `a post-flip re-forward sharing the file name still keeps the directory`() {
        // Deliberate: the directory DOES hold that name, so the legacy file is this row's only rescue
        // source once the server copy expires. Reclaiming the bytes must not cost the user their last
        // copy — the directory is kept until the row's own copy lands.
        setUpLegacy()
        plaintext(legacy, "shared.jpg", "aaa")
        val migrated = newDirectory("local-1").also { plaintext(it, "shared.jpg", "aaa") }
        val reForward = newDirectory("local-2")

        assertFalse(
            LegacyAttachmentFiles.isFullyMigrated(
                legacy,
                listOf(claim(migrated, "shared.jpg", 3), claim(reForward, "shared.jpg", 3))
            )
        )

        plaintext(reForward, "shared.jpg", "aaa")
        assertTrue(
            LegacyAttachmentFiles.isFullyMigrated(
                legacy,
                listOf(claim(migrated, "shared.jpg", 3), claim(reForward, "shared.jpg", 3))
            )
        )
    }

    @Test
    fun `a ciphertext-only legacy file still makes its row a claimant`() {
        // "Held" is decided on either on-disk shape: a row whose legacy copy exists only as
        // ciphertext must not be filtered out of the proof.
        setUpLegacy()
        ciphertext(legacy, "photo.jpg")
        val notLanded = newDirectory("local-1")

        assertFalse(LegacyAttachmentFiles.isFullyMigrated(legacy, listOf(claim(notLanded, "photo.jpg", 3))))
    }

    @Test
    fun `a sibling whose copy is truncated blocks the deletion`() {
        setUpLegacy()
        plaintext(legacy, "shared.jpg", "aaaaa")
        val landed = newDirectory("local-1").also { plaintext(it, "shared.jpg", "aaaaa") }
        val truncated = newDirectory("local-2").also { plaintext(it, "shared.jpg", "aa") }

        assertFalse(
            LegacyAttachmentFiles.isFullyMigrated(
                legacy,
                listOf(claim(landed, "shared.jpg", 5), claim(truncated, "shared.jpg", 5))
            )
        )
    }

    @Test
    fun `leftover temp files never block a deletion`() {
        setUpLegacy()
        plaintext(legacy, "a.jpg", "aaa")
        plaintext(legacy, "a.jpg${LegacyAttachmentFiles.TEMP_SUFFIX}", "half")
        plaintext(legacy, "a.jpg.encrypt.tmp", "half")
        val current = newDirectory("local-1").also { plaintext(it, "a.jpg", "aaa") }

        assertTrue(LegacyAttachmentFiles.isFullyMigrated(legacy, listOf(claim(current, "a.jpg", 3))))
    }

    @Test
    fun `a nested directory keeps the legacy directory alive`() {
        setUpLegacy()
        File(legacy, "nested").mkdirs()

        assertFalse(LegacyAttachmentFiles.isFullyMigrated(legacy, emptyList()))
    }

    @Test
    fun `deleting a legacy directory removes its files and drops their cached validity`() {
        setUpLegacy()
        val file = plaintext(legacy, "a.jpg", "aaa")
        assertTrue(FileUtil.isFileValid(file.path))

        assertTrue(LegacyAttachmentFiles.deleteDirectory(legacy))

        assertFalse(legacy.exists())
        assertFalse("a deleted path must stop reporting valid", FileUtil.isFileValid(file.path))
    }

    @Test
    fun `stray migrating files are swept, real files are not`() {
        val root = temp.newFolder("attachment")
        val directory = File(root, "local-1")
        plaintext(directory, "photo.jpg", "real")
        val old = System.currentTimeMillis() - LegacyAttachmentFiles.STRAY_TEMP_MIN_AGE_MS - 1_000
        plaintext(directory, "photo.jpg${LegacyAttachmentFiles.TEMP_SUFFIX}", "half").setLastModified(old)
        plaintext(directory, "photo.jpg.encrypt${LegacyAttachmentFiles.TEMP_SUFFIX}", "half").setLastModified(old)

        assertEquals(2, LegacyAttachmentFiles.sweepStrayTempFiles(root))

        assertTrue(File(directory, "photo.jpg").exists())
        assertFalse(File(directory, "photo.jpg${LegacyAttachmentFiles.TEMP_SUFFIX}").exists())
    }

    @Test
    fun `a fresh migrating temp is presumed in-flight and never swept`() {
        val root = temp.newFolder("attachment")
        val directory = File(root, "local-1")
        plaintext(directory, "photo.jpg${LegacyAttachmentFiles.TEMP_SUFFIX}", "in-flight")

        assertEquals(0, LegacyAttachmentFiles.sweepStrayTempFiles(root))
        assertTrue(File(directory, "photo.jpg${LegacyAttachmentFiles.TEMP_SUFFIX}").exists())
    }

    @Test
    fun `an encrypt temp is left to its own writer`() {
        // The live sink of an in-flight download: a large file on a slow link legitimately holds it
        // far past the age filter, and both of its writers drop it at the start of their next run.
        val root = temp.newFolder("attachment")
        val directory = File(root, "local-1")
        val old = System.currentTimeMillis() - LegacyAttachmentFiles.STRAY_TEMP_MIN_AGE_MS - 1_000
        plaintext(directory, "photo.jpg.encrypt.tmp", "still streaming").setLastModified(old)

        assertEquals(0, LegacyAttachmentFiles.sweepStrayTempFiles(root))
        assertTrue(File(directory, "photo.jpg.encrypt.tmp").exists())
    }

    @Test
    fun `sweeping an absent attachment root is a no-op`() {
        assertEquals(0, LegacyAttachmentFiles.sweepStrayTempFiles(File(temp.root, "missing")))
    }

    @Test
    fun `a copy leaves no temp file behind`() {
        plaintext(setUpLegacy(), "photo.jpg", "bytes")
        val target = newDirectory("local-1")

        LegacyAttachmentFiles.materialize(listOf(legacy), target, "photo.jpg")

        assertTrue(target.listFiles()!!.none { it.name.endsWith(LegacyAttachmentFiles.TEMP_SUFFIX) })
    }

    @Test
    fun `a single-reference legacy directory is renamed out of, not copied`() {
        val source = plaintext(setUpLegacy(), "photo.jpg", "bytes")
        val target = newDirectory("local-1")

        val outcome = LegacyAttachmentFiles.materialize(listOf(legacy), target, "photo.jpg", movableSource = legacy)

        assertEquals(LegacyAttachmentFiles.CopyOutcome.COPIED, outcome)
        assertEquals("bytes", File(target, "photo.jpg").readText())
        assertFalse("single-reference source must be moved away, not duplicated", source.exists())
    }

    @Test
    fun `both on-disk shapes move together`() {
        setUpLegacy()
        val plain = plaintext(legacy, "photo.jpg", "bytes")
        val cipher = ciphertext(legacy, "photo.jpg")
        val target = newDirectory("local-1")

        val outcome = LegacyAttachmentFiles.materialize(listOf(legacy), target, "photo.jpg", movableSource = legacy)

        assertEquals(LegacyAttachmentFiles.CopyOutcome.COPIED, outcome)
        assertTrue(File(target, "photo.jpg").isFile)
        assertTrue(File(target, "photo.jpg.encrypt").isFile)
        assertFalse(plain.exists())
        assertFalse(cipher.exists())
    }

    @Test
    fun `a movable grant that is not the actual source still copies`() {
        // The file is found in the owner-message directory; the movable proof covers only the
        // authorityId directory, so the transfer must fall back to a copy.
        val ownerDirectory = temp.newFolder("1700000000001")
        val source = plaintext(ownerDirectory, "photo.jpg", "bytes")
        val movableButEmpty = setUpLegacy()
        val target = newDirectory("local-1")

        val outcome = LegacyAttachmentFiles.materialize(
            listOf(movableButEmpty, ownerDirectory), target, "photo.jpg", movableSource = movableButEmpty
        )

        assertEquals(LegacyAttachmentFiles.CopyOutcome.COPIED, outcome)
        assertEquals("bytes", File(target, "photo.jpg").readText())
        assertTrue("a source outside the movable directory must survive", source.exists())
    }

    @Test
    fun `an emptied single-reference directory sweeps clean`() {
        plaintext(setUpLegacy(), "photo.jpg", "bytes")
        val target = newDirectory("local-1")
        LegacyAttachmentFiles.materialize(listOf(legacy), target, "photo.jpg", movableSource = legacy)

        assertTrue(LegacyAttachmentFiles.isFullyMigrated(legacy, listOf(claim(target, "photo.jpg", 5))))
        assertTrue(LegacyAttachmentFiles.deleteDirectory(legacy))
        assertFalse(legacy.exists())
    }
}
