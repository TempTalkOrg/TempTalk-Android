package com.difft.android.chat.attachment.migration

import com.difft.android.chat.attachment.migration.ForwardAttachmentMigration.Companion.startingStageFor
import kotlinx.coroutines.test.runTest
import org.difft.app.database.LegacyAttachmentAddresses
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test










class AttachmentMigrationVersionTest {

    
    private val stages = listOf(
        ForwardAttachmentMigration.Stage(ForwardAttachmentMigration.STAGE_BACKFILLED, 1) { true },
        ForwardAttachmentMigration.Stage(ForwardAttachmentMigration.STAGE_FILES_MIGRATED, 1) { true },
        ForwardAttachmentMigration.Stage(ForwardAttachmentMigration.STAGE_SWEPT, 1) { true },
        ForwardAttachmentMigration.Stage(ForwardAttachmentMigration.STAGE_NORMAL_MIGRATED, 2) { true },
        ForwardAttachmentMigration.Stage(ForwardAttachmentMigration.STAGE_FINAL_CLEANUP, 2) { true }
    )

    @After
    fun tearDown() {
        LegacyAttachmentAddresses.isWindowOpen = true
    }

    @Test
    fun `a fresh install starts at the first stage`() {
        assertEquals(0, startingStageFor(completedVersion = 0, stages = stages))
    }

    @Test
    fun `a device that completed version 1 is seeded past every version 1 stage`() {
        
        assertEquals(
            ForwardAttachmentMigration.STAGE_SWEPT,
            startingStageFor(completedVersion = 1, stages = stages)
        )
    }

    @Test
    fun `a device interrupted mid version 1 is seeded nothing and resumes from its own stage`() {
        
        
        assertEquals(0, startingStageFor(completedVersion = 0, stages = stages))
    }

    @Test
    fun `a device already on the target version has every stage seeded`() {
        assertEquals(
            ForwardAttachmentMigration.STAGE_FINAL_CLEANUP,
            startingStageFor(completedVersion = ForwardAttachmentMigration.TARGET_VERSION, stages = stages)
        )
    }

    @Test
    fun `a version beyond anything this build knows seeds every stage, never past the list`() {
        assertEquals(
            ForwardAttachmentMigration.STAGE_FINAL_CLEANUP,
            startingStageFor(completedVersion = 99, stages = stages)
        )
    }

    @Test
    fun `an empty stage list seeds nothing`() {
        assertEquals(0, startingStageFor(completedVersion = 5, stages = emptyList()))
    }

    @Test
    fun `the legacy deletion window is open while the migration is unfinished`() = runTest {
        LegacyAttachmentAddresses.isWindowOpen = false
        val store = FakePreferencesDataStore()
        AttachmentMigrationState(store).stampVersion(ForwardAttachmentMigration.TARGET_VERSION - 1)

        ForwardAttachmentMigration(store).readState()

        assertTrue(
            "a file may still sit at a legacy address until the target version is stamped",
            LegacyAttachmentAddresses.isWindowOpen
        )
    }

    @Test
    fun `the legacy deletion window closes once the target version is stamped`() = runTest {
        val store = FakePreferencesDataStore()
        val migration = ForwardAttachmentMigration(store)
        migration.readState()

        migration.stampVersion()

        assertFalse(LegacyAttachmentAddresses.isWindowOpen)
    }

    @Test
    fun `a device that already completed the target version closes the window on the first read`() = runTest {
        val store = FakePreferencesDataStore()
        AttachmentMigrationState(store).stampVersion(ForwardAttachmentMigration.TARGET_VERSION)

        ForwardAttachmentMigration(store).readState()

        assertFalse(LegacyAttachmentAddresses.isWindowOpen)
    }
}
