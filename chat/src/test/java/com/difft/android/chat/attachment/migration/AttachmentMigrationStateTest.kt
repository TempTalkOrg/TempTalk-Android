package com.difft.android.chat.attachment.migration

import com.difft.android.base.storage.AppStateKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test








class AttachmentMigrationStateTest {

    private val store = FakePreferencesDataStore()
    private val state = AttachmentMigrationState(store)

    @Test
    fun `a fresh install has migrated nothing`() = runTest {
        assertEquals(0, state.completedVersion())
        assertEquals(0, state.stage())
        assertEquals(0, state.watermark())
    }

    @Test
    fun `stage advances forward and refuses to regress`() = runTest {
        assertEquals(1, state.advanceStage(1))
        assertEquals(2, state.advanceStage(2))

        assertEquals("a stale run must not un-finish a stage", 2, state.advanceStage(1))
        assertEquals(2, state.stage())
    }

    @Test
    fun `an interrupted file stage resumes from its watermark`() = runTest {
        state.advanceStage(ForwardAttachmentMigration.STAGE_BACKFILLED)
        state.advanceWatermark(340)

        
        val resumed = AttachmentMigrationState(store)
        assertEquals(ForwardAttachmentMigration.STAGE_BACKFILLED, resumed.stage())
        assertEquals(340, resumed.watermark())

        assertEquals(520, resumed.advanceWatermark(520))
        assertEquals("a page replayed after a crash must not rewind the watermark", 520, resumed.advanceWatermark(400))
    }

    @Test
    fun `finishing a stage drops the watermark it left behind`() = runTest {
        state.advanceStage(ForwardAttachmentMigration.STAGE_FILES_MIGRATED)
        state.advanceWatermark(900)

        state.advanceStage(ForwardAttachmentMigration.STAGE_SWEPT)

        
        
        assertEquals("the watermark belongs to the stage in flight", 0, state.watermark())
        assertNull(store.data.first()[AppStateKeys.ATTACHMENT_MIGRATION_WATERMARK])
    }

    @Test
    fun `a stale stage report leaves the in-flight watermark alone`() = runTest {
        state.advanceStage(ForwardAttachmentMigration.STAGE_FILES_MIGRATED)
        state.advanceWatermark(900)

        state.advanceStage(ForwardAttachmentMigration.STAGE_BACKFILLED)

        assertEquals(900, state.watermark())
    }

    @Test
    fun `stamping a version clears the progress that got it there`() = runTest {
        state.advanceStage(ForwardAttachmentMigration.STAGE_FILES_MIGRATED)
        state.advanceWatermark(900)

        state.stampVersion(ForwardAttachmentMigration.TARGET_VERSION)

        assertEquals(ForwardAttachmentMigration.TARGET_VERSION, state.completedVersion())
        assertEquals("a later wave starts its own stage list from zero", 0, state.stage())
        assertEquals(0, state.watermark())
        assertNull(store.data.first()[AppStateKeys.ATTACHMENT_MIGRATION_STAGE])
        assertNull(store.data.first()[AppStateKeys.ATTACHMENT_MIGRATION_WATERMARK])
    }

    @Test
    fun `a completed version is never lowered`() = runTest {
        state.stampVersion(2)

        state.stampVersion(1)

        assertEquals(2, state.completedVersion())
    }

    @Test
    fun `file migration attempts count up and are cleared by stamping`() = runTest {
        assertEquals(0, state.fileAttempts())
        assertEquals(1, state.recordFileAttempt())
        assertEquals(2, state.recordFileAttempt())

        state.stampVersion(ForwardAttachmentMigration.TARGET_VERSION)

        assertEquals(0, state.fileAttempts())
        assertNull(store.data.first()[AppStateKeys.ATTACHMENT_MIGRATION_FILE_ATTEMPTS])
    }

    @Test
    fun `file migration attempts survive a restart`() = runTest {
        state.recordFileAttempt()
        state.recordFileAttempt()

        
        
        val resumed = AttachmentMigrationState(store)
        assertEquals(2, resumed.fileAttempts())
        assertEquals(3, resumed.recordFileAttempt())
        assertEquals("the budget is one shared counter, not one per instance", 3, state.fileAttempts())
    }

    @Test
    fun `the three give-up counters are independent`() = runTest {
        state.recordFileAttempt()
        state.recordFileAttempt()
        state.recordFileAttempt()

        assertEquals(3, state.fileAttempts())
        assertEquals("stage 2 giving up must not spend stage 3's budget", 0, state.sweepAttempts())
        
        
        
        assertEquals("stage 2 giving up must not spend stage 4's budget", 0, state.normalAttempts())

        state.recordSweepAttempt()
        assertEquals("stage 3 giving up must not spend stage 4's budget", 0, state.normalAttempts())

        state.recordNormalAttempt()
        assertEquals("stage 4 giving up must not spend stage 2's budget", 3, state.fileAttempts())
        assertEquals("stage 4 giving up must not spend stage 3's budget", 1, state.sweepAttempts())
    }

    @Test
    fun `sweep attempts count up and are cleared by stamping`() = runTest {
        assertEquals(0, state.sweepAttempts())
        assertEquals(1, state.recordSweepAttempt())
        assertEquals(2, state.recordSweepAttempt())

        state.stampVersion(ForwardAttachmentMigration.TARGET_VERSION)

        assertEquals(0, state.sweepAttempts())
        assertNull(store.data.first()[AppStateKeys.ATTACHMENT_MIGRATION_SWEEP_ATTEMPTS])
    }

    @Test
    fun `normal migration attempts count up and are cleared by stamping`() = runTest {
        assertEquals(0, state.normalAttempts())
        assertEquals(1, state.recordNormalAttempt())
        assertEquals(2, state.recordNormalAttempt())

        state.stampVersion(ForwardAttachmentMigration.TARGET_VERSION)

        assertEquals(0, state.normalAttempts())
        assertNull(store.data.first()[AppStateKeys.ATTACHMENT_MIGRATION_NORMAL_ATTEMPTS])
    }

    @Test
    fun `normal migration attempts survive a restart`() = runTest {
        state.recordNormalAttempt()
        state.recordNormalAttempt()

        
        
        
        val resumed = AttachmentMigrationState(store)
        assertEquals(2, resumed.normalAttempts())
        assertEquals(3, resumed.recordNormalAttempt())
        assertEquals("the budget is one shared counter, not one per instance", 3, state.normalAttempts())
    }
}
