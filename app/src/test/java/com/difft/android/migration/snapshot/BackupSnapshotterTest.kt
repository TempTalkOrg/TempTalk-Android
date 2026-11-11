package com.difft.android.migration.snapshot

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.migration.model.MigrationException
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import org.difft.app.database.WCDB
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue







@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupSnapshotterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var wcdb: WCDB
    private lateinit var dbOps: SnapshotDbOps
    private lateinit var snapshotter: BackupSnapshotterImpl

    
    private lateinit var sourceDbFile: File
    private val sourceBytes = "hello-snapshot-source".toByteArray()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        
        sourceDbFile = context.getDatabasePath(WCDB.DATABASE_NAME)
        sourceDbFile.parentFile?.mkdirs()
        sourceDbFile.writeBytes(sourceBytes)

        
        
        wcdb = mockk(relaxed = true)
        dbOps = mockk(relaxed = false)
        every { dbOps.execute(any()) } just Runs
        every { dbOps.passiveCheckpoint() } just Runs
        every { dbOps.truncateCheckpoint() } just Runs

        snapshotter = BackupSnapshotterImpl(context, wcdb).apply {
            overrideOps = dbOps
        }
    }

    @After
    fun tearDown() {
        sourceDbFile.delete()
        File(context.filesDir, "migration_temp").deleteRecursively()
    }

    

    @Test
    fun `takeSnapshot disables autoCheckpoint first then runs checkpoints then restores in finally`() = runTest {
        val out = snapshotter.takeSnapshot()

        
        assertTrue(out.exists(), "snapshot tmp file should exist")
        assertEquals(File(context.filesDir, "migration_temp/chat.db.tmp").absolutePath, out.absolutePath)
        assertContentEquals(sourceBytes, out.readBytes())

        
        verifyOrder {
            dbOps.execute("PRAGMA wal_autocheckpoint = 0")
            dbOps.passiveCheckpoint()
            dbOps.truncateCheckpoint()
            dbOps.execute("PRAGMA wal_autocheckpoint = 1000")
        }
    }

    @Test
    fun `passiveCheckpoint failure is swallowed and truncateCheckpoint still runs`() = runTest {
        every { dbOps.passiveCheckpoint() } throws RuntimeException("passive failed")

        val out = snapshotter.takeSnapshot()

        assertTrue(out.exists())
        verifyOrder {
            dbOps.execute("PRAGMA wal_autocheckpoint = 0")
            dbOps.passiveCheckpoint()                 
            dbOps.truncateCheckpoint()                
            dbOps.execute("PRAGMA wal_autocheckpoint = 1000")
        }
    }

    

    @Test
    fun `truncateCheckpoint retries and succeeds on second attempt`() = runTest {
        var attempts = 0
        every { dbOps.truncateCheckpoint() } answers {
            attempts += 1
            if (attempts < 2) throw RuntimeException("SQLITE_BUSY attempt=$attempts")
            
        }

        val out = snapshotter.takeSnapshot()

        assertTrue(out.exists(), "snapshot succeeds after retry")
        verify(exactly = 2) { dbOps.truncateCheckpoint() }
        
        verify { dbOps.execute("PRAGMA wal_autocheckpoint = 1000") }
    }

    @Test
    fun `truncateCheckpoint exhausts retries throws SnapshotFailed and restores PRAGMA`() = runTest {
        every { dbOps.truncateCheckpoint() } throws RuntimeException("SQLITE_BUSY persistent")

        val ex = assertFailsWith<MigrationException.SnapshotFailed> {
            snapshotter.takeSnapshot()
        }

        assertTrue(ex.message?.contains("truncateCheckpoint exhausted retries") == true)
        
        verify(exactly = 3) { dbOps.truncateCheckpoint() }

        
        verifyOrder {
            dbOps.execute("PRAGMA wal_autocheckpoint = 0")
            dbOps.execute("PRAGMA wal_autocheckpoint = 1000")
        }

        
        val tmpFile = File(context.filesDir, "migration_temp/chat.db.tmp")
        assertFalse(tmpFile.exists(), "no copy when snapshot failed")
    }

    

    @Test
    fun `restore PRAGMA is called even when copy step throws`() = runBlocking {
        
        sourceDbFile.delete()

        val thrown = runCatching { snapshotter.takeSnapshot() }.exceptionOrNull()
        
        assertNotNull(thrown, "copy should have thrown")

        verify { dbOps.execute("PRAGMA wal_autocheckpoint = 0") }
        verify { dbOps.execute("PRAGMA wal_autocheckpoint = 1000") }
    }

    

    @Test
    fun `TimeoutCancellationException from inner op never escapes as a CancellationException`() = runBlocking {
        
        
        
        

        
        
        
        
        val realTimeoutEx: TimeoutCancellationException = try {
            kotlinx.coroutines.withTimeout(1L) { kotlinx.coroutines.delay(1000L) }
            error("withTimeout did not throw — test setup invalid")
        } catch (e: TimeoutCancellationException) {
            e
        }

        every { dbOps.truncateCheckpoint() } throws realTimeoutEx

        val ex = assertFailsWith<MigrationException.SnapshotFailed> {
            snapshotter.takeSnapshot()
        }

        
        
        
        
        
        
        
        val thrown: Throwable = ex
        assertFalse(
            thrown is CancellationException,
            "SnapshotFailed must NOT be a CancellationException so the Service's " +
                "`catch (CancellationException)` doesn't grab it and mis-classify as Cancelled",
        )
        
        verify { dbOps.execute("PRAGMA wal_autocheckpoint = 1000") }
    }

    @Test
    fun `TimeoutCancellationException catch does not catch plain CancellationException superclass`() = runBlocking {
        
        
        
        

        
        
        
        
        val realTimeoutEx: TimeoutCancellationException = try {
            kotlinx.coroutines.withTimeout(1L) { kotlinx.coroutines.delay(1000L) }
            error("withTimeout did not throw — test setup invalid")
        } catch (e: TimeoutCancellationException) {
            e
        }
        val timeoutEx: CancellationException = realTimeoutEx
        assertTrue(timeoutEx is TimeoutCancellationException)

        val plainCancelEx: CancellationException = CancellationException("user cancelled")
        assertFalse(
            plainCancelEx is TimeoutCancellationException,
            "Plain CancellationException must NOT match the TimeoutCancellationException " +
                "catch — otherwise user cancellation would be mis-classified as a snapshot timeout",
        )
    }

    

    @Test
    fun `pre-existing tmp file is deleted before copy`() = runTest {
        
        val tmpDir = File(context.filesDir, "migration_temp").apply { mkdirs() }
        val staleTmp = File(tmpDir, "chat.db.tmp")
        staleTmp.writeBytes("STALE-CONTENT".toByteArray())

        val out = snapshotter.takeSnapshot()

        
        assertContentEquals(sourceBytes, out.readBytes())
        assertFalse(out.readBytes().contentEquals("STALE-CONTENT".toByteArray()))
    }
}
