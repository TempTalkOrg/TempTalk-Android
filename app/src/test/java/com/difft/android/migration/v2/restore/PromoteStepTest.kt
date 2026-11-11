package com.difft.android.migration.v2.restore

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.log.WCDBKeyUnavailableException
import com.difft.android.migration.model.MigrationException
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.difft.app.database.WCDBKeyManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue









@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PromoteStepTest {

    private lateinit var context: Context
    private lateinit var markerStore: PromoteMarkerStore
    private lateinit var step: PromoteStep
    private lateinit var workDir: File

    
    private val stagingKey = ByteArray(48) { (it + 1).toByte() }
    private val protectedBlob = ByteArray(76) { (it * 3).toByte() }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        markerStore = PromoteMarkerStore(context)
        
        step = PromoteStep(context, markerStore, UnconfinedTestDispatcher())
        workDir = File(context.cacheDir, "promote-test-${System.nanoTime()}").apply { mkdirs() }

        
        mockkObject(WCDBKeyManager)
        every { WCDBKeyManager.protectKey(any()) } returns protectedBlob
        every { WCDBKeyManager.unprotectKey(any()) } returns stagingKey
        every { WCDBKeyManager.installKey(any(), any()) } returns Unit

        markerStore.clear()
    }

    @After
    fun teardown() {
        unmockkObject(WCDBKeyManager)
        markerStore.clear()
        workDir.deleteRecursively()
    }

    

    @Test
    fun `promote moves db plus attachments and clears marker`() = runTest {
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        File(workDir, "staging.db-wal").writeBytes(byteArrayOf(9))
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        File(attStaging, "a.bin").writeBytes(byteArrayOf(5, 6))
        File(attStaging, "sub").apply { mkdirs() }.let { File(it, "b.bin").writeBytes(byteArrayOf(7)) }

        val realDb = File(workDir, "real/tt_wcdb_database.db")
        val realAtt = File(workDir, "real-att")

        step.promote(
            stagingDbPath = stagingDb.absolutePath,
            stagingKey = stagingKey,
            attachmentsStagingDir = attStaging.absolutePath,
            realDbPath = realDb.absolutePath,
            realAttachmentDir = realAtt.absolutePath,
        )

        
        assertTrue(realDb.exists(), "real db must exist after promote")
        assertContentEquals(byteArrayOf(1, 2, 3, 4), realDb.readBytes())
        assertTrue(File(realDb.absolutePath + "-wal").exists(), "-wal sidecar must move too")
        assertFalse(stagingDb.exists(), "staging db must be moved away")

        
        assertContentEquals(byteArrayOf(5, 6), File(realAtt, "a.bin").readBytes())
        assertContentEquals(byteArrayOf(7), File(realAtt, "sub/b.bin").readBytes())

        
        verify(exactly = 1) { WCDBKeyManager.installKey(context, stagingKey) }

        
        assertFalse(markerStore.exists(), "marker must be cleared at P4")
    }

    @Test
    fun `promote removes the live db's stale sidecars before the staging db lands`() = runTest {
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        val realDb = File(workDir, "real/tt_wcdb_database.db").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(0)) }
        
        val stale = listOf("-wal", "-shm", "-first.material", "-last.material", "-incremental.material")
            .map { File(realDb.absolutePath + it).apply { writeBytes(byteArrayOf(0x55)) } }

        step.promote(
            stagingDbPath = stagingDb.absolutePath,
            stagingKey = stagingKey,
            attachmentsStagingDir = attStaging.absolutePath,
            realDbPath = realDb.absolutePath,
            realAttachmentDir = File(workDir, "real-att").absolutePath,
        )

        assertContentEquals(byteArrayOf(1, 2, 3, 4), realDb.readBytes())
        stale.forEach { assertFalse(it.exists(), "${it.name} from the old live db must not survive the promote") }
    }

    

    @Test
    fun `promote with a null staging key skips key install and still moves db (plaintext debug)`() = runTest {
        
        
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        File(attStaging, "a.bin").writeBytes(byteArrayOf(5))
        val realDb = File(workDir, "real/tt_wcdb_database.db")
        val realAtt = File(workDir, "real-att")

        step.promote(
            stagingDbPath = stagingDb.absolutePath,
            stagingKey = null,
            attachmentsStagingDir = attStaging.absolutePath,
            realDbPath = realDb.absolutePath,
            realAttachmentDir = realAtt.absolutePath,
        )

        
        assertContentEquals(byteArrayOf(1, 2, 3), realDb.readBytes())
        assertContentEquals(byteArrayOf(5), File(realAtt, "a.bin").readBytes())
        assertFalse(markerStore.exists(), "marker must be cleared at P4")
        
        verify(exactly = 0) { WCDBKeyManager.installKey(any(), any()) }
        verify(exactly = 0) { WCDBKeyManager.protectKey(any()) }
        verify(exactly = 0) { WCDBKeyManager.unprotectKey(any()) }
    }

    @Test
    fun `resumeIfPending re-does a null-key (debug plaintext) promote without installing a key`() = runTest {
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(4, 4)) }
        val realDb = File(workDir, "real/tt_wcdb_database.db")
        val realAtt = File(workDir, "real-att")

        
        markerStore.write(
            PromoteMarker.create(
                stagingDbPath = stagingDb.absolutePath,
                stagingKey = null,
                attachmentsStagingDir = File(workDir, "att-staging").apply { mkdirs() }.absolutePath,
                realDbPath = realDb.absolutePath,
                realAttachmentDir = realAtt.absolutePath,
            )
        )

        assertEquals(PromoteStep.ResumeResult.ResumedWithData, step.resumeIfPending())
        assertContentEquals(byteArrayOf(4, 4), realDb.readBytes())
        assertFalse(markerStore.exists())
        verify(exactly = 0) { WCDBKeyManager.installKey(any(), any()) }
    }

    

    @Test
    fun `promote does not re-assert net-install and proceeds over a pre-existing real db (caller asserted)`() = runTest {
        
        
        
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(1)) }
        val realDb = File(workDir, "tt_wcdb_database.db").apply { writeBytes(byteArrayOf(42)) } 

        step.promote(
            stagingDbPath = stagingDb.absolutePath,
            stagingKey = stagingKey,
            attachmentsStagingDir = File(workDir, "att").absolutePath,
            realDbPath = realDb.absolutePath,
            realAttachmentDir = File(workDir, "real-att").absolutePath,
        )
        
        assertContentEquals(byteArrayOf(1), realDb.readBytes())
        assertFalse(stagingDb.exists())
        assertFalse(markerStore.exists())
        verify(exactly = 1) { WCDBKeyManager.installKey(context, stagingKey) }
    }

    @Test
    fun `promote allows a zero-byte real db (net-install)`() = runTest {
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(1)) }
        val realDb = File(workDir, "tt_wcdb_database.db").apply { createNewFile() } 

        step.promote(
            stagingDbPath = stagingDb.absolutePath,
            stagingKey = stagingKey,
            attachmentsStagingDir = File(workDir, "att").absolutePath,
            realDbPath = realDb.absolutePath,
            realAttachmentDir = File(workDir, "real-att").absolutePath,
        )
        assertContentEquals(byteArrayOf(1), realDb.readBytes())
        assertFalse(markerStore.exists())
    }

    

    @Test
    fun `resumeIfPending returns NothingToResume when no marker`() = runTest {
        assertEquals(PromoteStep.ResumeResult.NothingToResume, step.resumeIfPending())
    }

    @Test
    fun `resumeIfPending re-does P1 to P4 from a surviving marker`() = runTest {
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(8, 8)) }
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        File(attStaging, "c.bin").writeBytes(byteArrayOf(3))
        val realDb = File(workDir, "real/tt_wcdb_database.db")
        val realAtt = File(workDir, "real-att")

        
        markerStore.write(
            PromoteMarker.create(
                stagingDbPath = stagingDb.absolutePath,
                stagingKey = stagingKey,
                attachmentsStagingDir = attStaging.absolutePath,
                realDbPath = realDb.absolutePath,
                realAttachmentDir = realAtt.absolutePath,
            )
        )

        val resumed = step.resumeIfPending()

        assertEquals(PromoteStep.ResumeResult.ResumedWithData, resumed)
        assertContentEquals(byteArrayOf(8, 8), realDb.readBytes())
        assertContentEquals(byteArrayOf(3), File(realAtt, "c.bin").readBytes())
        verify(exactly = 1) { WCDBKeyManager.installKey(context, stagingKey) }
        assertFalse(markerStore.exists(), "marker must be cleared after a successful resume")
    }

    @Test
    fun `resumeIfPending discards a half-promote when all staging products are gone`() = runTest {
        val realDb = File(workDir, "real/tt_wcdb_database.db").apply {
            parentFile?.mkdirs(); createNewFile() 
        }
        
        markerStore.write(
            PromoteMarker.create(
                stagingDbPath = File(workDir, "gone-staging.db").absolutePath,
                stagingKey = stagingKey,
                attachmentsStagingDir = File(workDir, "gone-att").absolutePath,
                realDbPath = realDb.absolutePath,
                realAttachmentDir = File(workDir, "real-att").absolutePath,
            )
        )

        val resumed = step.resumeIfPending()

        
        assertEquals(PromoteStep.ResumeResult.DiscardedToCleanState, resumed)
        
        assertEquals(0L, realDb.length())
        assertFalse(markerStore.exists())
        verify(exactly = 0) { WCDBKeyManager.installKey(any(), any()) }
    }

    @Test
    fun `resumeIfPending discards on staging gone even when a schema-only real db is present (no byte-size check)`() = runTest {
        
        
        val realDb = File(workDir, "real/tt_wcdb_database.db").apply {
            parentFile?.mkdirs(); writeBytes(ByteArray(90112)) 
        }
        markerStore.write(
            PromoteMarker.create(
                stagingDbPath = File(workDir, "gone-staging.db").absolutePath,
                stagingKey = stagingKey,
                attachmentsStagingDir = File(workDir, "gone-att").absolutePath,
                realDbPath = realDb.absolutePath,
                realAttachmentDir = File(workDir, "real-att").absolutePath,
            )
        )

        val resumed = step.resumeIfPending()

        
        assertEquals(PromoteStep.ResumeResult.DiscardedToCleanState, resumed)
        assertFalse(markerStore.exists())
        verify(exactly = 0) { WCDBKeyManager.installKey(any(), any()) }
    }

    @Test
    fun `resumeIfPending finishes P3 when P2 already ran (db present, staging gone)`() = runTest {
        
        val realDb = File(workDir, "real/tt_wcdb_database.db").apply {
            parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 1))
        }
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        File(attStaging, "d.bin").writeBytes(byteArrayOf(4))
        val realAtt = File(workDir, "real-att")

        markerStore.write(
            PromoteMarker.create(
                stagingDbPath = File(workDir, "already-moved-staging.db").absolutePath,
                stagingKey = stagingKey,
                attachmentsStagingDir = attStaging.absolutePath,
                realDbPath = realDb.absolutePath,
                realAttachmentDir = realAtt.absolutePath,
            )
        )

        assertEquals(PromoteStep.ResumeResult.ResumedWithData, step.resumeIfPending())
        
        assertContentEquals(byteArrayOf(1, 1), realDb.readBytes())
        assertContentEquals(byteArrayOf(4), File(realAtt, "d.bin").readBytes())
        assertFalse(markerStore.exists())
    }

    @Test
    fun `P3 attachment move is idempotent (already-present dst is not overwritten)`() = runTest {
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(1)) }
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        File(attStaging, "e.bin").writeBytes(byteArrayOf(99)) 
        val realDb = File(workDir, "real/tt_wcdb_database.db")
        val realAtt = File(workDir, "real-att").apply { mkdirs() }
        File(realAtt, "e.bin").writeBytes(byteArrayOf(7)) 

        step.promote(
            stagingDbPath = stagingDb.absolutePath,
            stagingKey = stagingKey,
            attachmentsStagingDir = attStaging.absolutePath,
            realDbPath = realDb.absolutePath,
            realAttachmentDir = realAtt.absolutePath,
        )
        
        assertContentEquals(byteArrayOf(7), File(realAtt, "e.bin").readBytes())
        assertFalse(File(attStaging, "e.bin").exists())
    }

    @Test
    fun `P3 preserves canonical owner-dir layout and is per-file idempotent within an owner dir`() = runTest {
        
        
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(1)) }
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        
        val ownerDir = File(attStaging, "5566778899").apply { mkdirs() }
        File(ownerDir, "fwd.jpg").writeBytes(byteArrayOf(10, 11)) 
        File(ownerDir, "second.jpg").writeBytes(byteArrayOf(12))  
        
        File(attStaging, "m-image").apply { mkdirs() }.let { File(it, "photo.jpg").writeBytes(byteArrayOf(20)) }

        val realDb = File(workDir, "real/tt_wcdb_database.db")
        val realAtt = File(workDir, "real-att").apply { mkdirs() }
        
        File(realAtt, "5566778899").apply { mkdirs() }.let { File(it, "second.jpg").writeBytes(byteArrayOf(77)) }

        step.promote(
            stagingDbPath = stagingDb.absolutePath,
            stagingKey = stagingKey,
            attachmentsStagingDir = attStaging.absolutePath,
            realDbPath = realDb.absolutePath,
            realAttachmentDir = realAtt.absolutePath,
        )

        
        assertContentEquals(byteArrayOf(10, 11), File(realAtt, "5566778899/fwd.jpg").readBytes())
        assertContentEquals(byteArrayOf(20), File(realAtt, "m-image/photo.jpg").readBytes())
        
        
        assertContentEquals(byteArrayOf(77), File(realAtt, "5566778899/second.jpg").readBytes())
        assertFalse(File(ownerDir, "fwd.jpg").exists(), "moved staging file removed")
        assertFalse(File(ownerDir, "second.jpg").exists(), "already-present staging copy dropped")
        assertFalse(markerStore.exists(), "marker cleared at P4")
    }

    

    @Test
    fun `P3 attachment move failure throws and keeps marker, then resume retries and completes`() = runTest {
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(1, 2)) }
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        File(attStaging, "f.bin").writeBytes(byteArrayOf(55))
        val realDb = File(workDir, "real/tt_wcdb_database.db")
        
        
        
        val realAtt = File(workDir, "real-att-blocker").apply { writeBytes(byteArrayOf(0)) }

        assertFailsWith<MigrationException.RestoreFailed> {
            step.promote(
                stagingDbPath = stagingDb.absolutePath,
                stagingKey = stagingKey,
                attachmentsStagingDir = attStaging.absolutePath,
                realDbPath = realDb.absolutePath,
                realAttachmentDir = realAtt.absolutePath,
            )
        }

        
        assertTrue(markerStore.exists(), "marker must survive a P3 attachment-move failure")
        
        assertTrue(realDb.exists(), "P2 db move happened before the P3 failure")
        assertTrue(File(attStaging, "f.bin").exists(), "attachment must remain in staging (not lost)")

        
        realAtt.delete()
        val resumed = step.resumeIfPending()

        assertEquals(PromoteStep.ResumeResult.ResumedWithData, resumed)
        assertContentEquals(byteArrayOf(55), File(realAtt, "f.bin").readBytes())
        assertFalse(markerStore.exists(), "marker cleared once P3 succeeds on retry")
    }

    

    @Test
    fun `P3 listFiles null on existing staging dir throws and keeps marker`() = runTest {
        
        val realDb = File(workDir, "real/tt_wcdb_database.db").apply {
            parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 1))
        }
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        File(attStaging, "h.bin").writeBytes(byteArrayOf(3))
        val realAtt = File(workDir, "real-att")

        
        
        
        val stepWithNullLister = PromoteStep(
            context, markerStore, UnconfinedTestDispatcher(),
            dirLister = { dir -> if (dir.absolutePath == attStaging.absolutePath) null else dir.listFiles() },
        )

        markerStore.write(
            PromoteMarker.create(
                stagingDbPath = File(workDir, "already-moved-staging.db").absolutePath,
                stagingKey = stagingKey,
                attachmentsStagingDir = attStaging.absolutePath,
                realDbPath = realDb.absolutePath,
                realAttachmentDir = realAtt.absolutePath,
            )
        )

        assertFailsWith<MigrationException.RestoreFailed> {
            stepWithNullLister.resumeIfPending()
        }

        
        assertTrue(markerStore.exists(), "marker must survive a listFiles()==null I/O error")
        assertTrue(File(attStaging, "h.bin").exists(), "attachment must remain in staging (not lost)")
    }

    

    @Test
    fun `resumeIfPending aborts to clean state when staging key is unrecoverable`() = runTest {
        
        
        every { WCDBKeyManager.unprotectKey(any()) } throws
            WCDBKeyUnavailableException("Keystore alias invalidated")

        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(7, 7)) }
        File(workDir, "staging.db-wal").writeBytes(byteArrayOf(1))
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        File(attStaging, "g.bin").writeBytes(byteArrayOf(9))
        val realDb = File(workDir, "real/tt_wcdb_database.db").apply {
            parentFile?.mkdirs(); createNewFile() 
        }
        val realAtt = File(workDir, "real-att")

        markerStore.write(
            PromoteMarker.create(
                stagingDbPath = stagingDb.absolutePath,
                stagingKey = stagingKey,
                attachmentsStagingDir = attStaging.absolutePath,
                realDbPath = realDb.absolutePath,
                realAttachmentDir = realAtt.absolutePath,
            )
        )

        
        assertFailsWith<MigrationException.RestoreAbortedCleanState> {
            step.resumeIfPending()
        }

        
        assertFalse(markerStore.exists(), "marker must be cleared on unrecoverable abort")
        assertFalse(stagingDb.exists(), "staging db discarded on abort")
        assertFalse(File(workDir, "staging.db-wal").exists(), "staging -wal sidecar discarded on abort")
        assertFalse(attStaging.exists(), "staging attachment dir discarded on abort")
        assertEquals(0L, realDb.length(), "empty real DB untouched (net-install invariant)")
        verify(exactly = 0) { WCDBKeyManager.installKey(any(), any()) }

        
        val second = step.resumeIfPending()
        assertEquals(
            PromoteStep.ResumeResult.NothingToResume, second,
            "no marker remains → nothing to resume (loop is impossible)",
        )
    }

    

    @Test
    fun `resumeIfPending discards to clean state on a corrupt (unparseable) marker`() = runTest {
        
        File(context.filesDir, PromoteMarkerStore.MARKER_FILE_NAME)
            .writeText("{ this is not valid json for a marker ]]]")

        val result = step.resumeIfPending()

        assertEquals(PromoteStep.ResumeResult.DiscardedToCleanState, result)
        
        assertFalse(markerStore.exists(), "corrupt marker must be cleared")
        verify(exactly = 0) { WCDBKeyManager.installKey(any(), any()) }
        
        assertEquals(PromoteStep.ResumeResult.NothingToResume, step.resumeIfPending())
    }
}






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PromoteMarkerStoreTest {

    private lateinit var context: Context
    private lateinit var store: PromoteMarkerStore

    private fun sampleMarker() = PromoteMarker(
        stagingDbPath = "/data/staging.db",
        protectedStagingKeyB64 = "QUJDREVG",
        attachmentsStagingDir = "/data/att-staging",
        realDbPath = "/data/tt_wcdb_database.db",
        realAttachmentDir = "/data/attachment",
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        store = PromoteMarkerStore(context)
        store.clear()
    }

    @After
    fun teardown() {
        store.clear()
    }

    @Test
    fun `write then read round-trips every field`() {
        assertFalse(store.exists())
        val m = sampleMarker()
        store.write(m)

        assertTrue(store.exists())
        val read = store.read()
        assertEquals(m, read)
    }

    @Test
    fun `clear removes the marker`() {
        store.write(sampleMarker())
        assertTrue(store.exists())
        store.clear()
        assertFalse(store.exists())
        assertEquals(null, store.read())
    }

    @Test
    fun `read returns null when no marker on disk`() {
        assertEquals(null, store.read())
    }

    @Test
    fun `write leaves no temp file behind`() {
        store.write(sampleMarker())
        val tmp = File(context.filesDir, PromoteMarkerStore.MARKER_TMP_NAME)
        assertFalse(tmp.exists(), "atomic write must not leave a .tmp file after success")
    }

    

    @Test
    fun `readChecked returns Absent when no marker on disk`() {
        assertEquals(PromoteMarkerStore.ReadResult.Absent, store.readChecked())
    }

    @Test
    fun `readChecked returns Present for a valid marker`() {
        val m = sampleMarker()
        store.write(m)
        assertEquals(PromoteMarkerStore.ReadResult.Present(m), store.readChecked())
    }

    @Test
    fun `readChecked returns Corrupt for an unparseable marker file`() {
        File(context.filesDir, PromoteMarkerStore.MARKER_FILE_NAME).writeText("not-json ]]]")
        assertEquals(PromoteMarkerStore.ReadResult.Corrupt, store.readChecked())
        
        assertEquals(null, store.read())
    }

    

    @Test
    fun `create with a null staging key yields a null protected blob and stagingKey returns null`() {
        
        
        val marker = PromoteMarker.create(
            stagingDbPath = "/data/staging.db",
            stagingKey = null,
            attachmentsStagingDir = "/data/att",
            realDbPath = "/data/tt_wcdb_database.db",
            realAttachmentDir = "/data/attachment",
        )
        assertEquals(null, marker.protectedStagingKeyB64, "null staging key must persist as null (no protect)")
        assertEquals(null, marker.stagingKey(), "stagingKey() must return null when no key was protected")

        
        store.write(marker)
        assertEquals(marker, store.read())
    }
}
