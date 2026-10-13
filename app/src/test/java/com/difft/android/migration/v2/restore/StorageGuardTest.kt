package com.difft.android.migration.v2.restore

import android.system.ErrnoException
import android.system.OsConstants
import com.difft.android.migration.model.MigrationException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for [StorageGuard] — T28-T30 of the symmetric-transport design: the `prepare-upload`
 * formula, the ENOSPC classification (including the cause walk) and the tightest-volume rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class StorageGuardTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    private fun guardWith(vararg free: Long): StorageGuard {
        val volumes = free.indices.map { File("/volume-$it") }
        val byPath = volumes.zip(free.toList()).associate { (file, bytes) -> file.absolutePath to bytes }
        return StorageGuard(volumes) { byPath.getValue(it.absolutePath) }
    }

    // ── T28 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a declared size requires twice itself plus the fixed overhead and the promote margin`() {
        // 2 x 1 GiB + 128 MiB overhead + 256 MiB margin = 2 GiB + 384 MiB.
        assertFalse(guardWith(2 * gb + 383 * mb).precheck(gb).sufficient, "one MiB short must be refused")
        assertTrue(guardWith(2 * gb + 385 * mb).precheck(gb).sufficient, "one MiB over must be accepted")
        assertEquals(2 * gb + 384 * mb, guardWith(0).precheck(gb).requiredBytes)
    }

    @Test
    fun `an unknown size requires the flat floor`() {
        assertFalse(guardWith(400 * mb).precheck(0).sufficient)
        assertTrue(guardWith(600 * mb).precheck(0).sufficient)
        assertEquals(StorageGuard.UNKNOWN_SIZE_FLOOR_BYTES, guardWith(0).precheck(0).requiredBytes)
    }

    // ── T29 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `out-of-space is recognised by errno, by message and through the cause chain`() {
        assertTrue(StorageGuard.isOutOfSpace(ErrnoException("write", OsConstants.ENOSPC)))
        assertTrue(StorageGuard.isOutOfSpace(IOException("No space left on device")))
        assertTrue(StorageGuard.isOutOfSpace(RuntimeException("database or disk is full")))
        assertTrue(
            StorageGuard.isOutOfSpace(IllegalStateException("import failed", IOException("ENOSPC"))),
            "the cause chain is walked because WCDB and FileOutputStream both wrap",
        )
        assertFalse(StorageGuard.isOutOfSpace(IOException("permission denied")))
    }

    // ── T30 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the tightest volume decides`() {
        assertEquals(100 * mb, guardWith(10 * gb, 100 * mb).availableBytes())
    }

    @Test
    fun `requireHeadroom throws below the low-water mark and is silent above it`() {
        assertFailsWith<MigrationException.OutOfSpace> { guardWith(StorageGuard.LOW_WATER_BYTES - 1).requireHeadroom("spool") }
        guardWith(StorageGuard.LOW_WATER_BYTES).requireHeadroom("spool")
    }

    @Test
    fun `a volume that cannot be measured is skipped rather than treated as full`() {
        val ok = File("/ok")
        val broken = File("/broken")
        val guard = StorageGuard(listOf(ok, broken)) { volume ->
            if (volume.absolutePath == broken.absolutePath) throw IllegalStateException("statfs failed") else 5 * gb
        }
        assertEquals(5 * gb, guard.availableBytes())
    }
}
