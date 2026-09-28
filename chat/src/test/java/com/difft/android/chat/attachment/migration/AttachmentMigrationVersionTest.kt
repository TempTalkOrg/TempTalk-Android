package com.difft.android.chat.attachment.migration

import com.difft.android.chat.attachment.migration.ForwardAttachmentMigration.Companion.startingStageFor
import kotlinx.coroutines.test.runTest
import org.difft.app.database.LegacyAttachmentAddresses
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a device arrives at the version this build requires: which stages it may skip on the way, and
 * what it tells the deletion path while it is still on the way there.
 *
 * Both are decisions that only bite on an UPGRADE, which is the path least likely to be exercised by
 * hand, so they are pinned here rather than left to a device: stamping a version clears the in-flight
 * stage, and without seeding, a device that finished version 1 would re-run version 1's full-table
 * scan and full attachment-root sweep on every version-2 launch.
 */
class AttachmentMigrationVersionTest {

    /** The shape of the real list: three stages introduced by v1, two more by v2. */
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
        // Seeded past 3 = the next stage the loop runs is 4, without re-walking v1's scans.
        assertEquals(
            ForwardAttachmentMigration.STAGE_SWEPT,
            startingStageFor(completedVersion = 1, stages = stages)
        )
    }

    @Test
    fun `a device interrupted mid version 1 is seeded nothing and resumes from its own stage`() {
        // completedVersion is still 0 for it — the seed must not claim any stage is finished, so the
        // recorded in-flight stage is what decides where it resumes.
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
