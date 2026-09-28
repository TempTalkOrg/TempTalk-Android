package com.difft.android.chat.translate.model

import android.app.DownloadManager
import android.content.Context
import android.database.MatrixCursor
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TranslateModelDownloadProbe] row ownership.
 *
 * `DownloadManager.query` is UID-scoped but not app-feature-scoped, and ML Kit sets neither a title
 * nor a description on its Request — the model URI is the only discriminator. A row this app did not
 * enqueue for a translation model must never be read as translation progress.
 *
 * Robolectric for `MatrixCursor` only; the DownloadManager itself is a MockK.
 *
 * What each case asserts:
 *  - T24b a non-ML-Kit row in the same result set is ignored, the ML Kit row is returned
 *  - T53  (partial) the column reads are correct for a well-formed row
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslateModelDownloadProbeTest {

    private lateinit var downloadManager: DownloadManager
    private lateinit var context: Context
    private lateinit var probe: TranslateModelDownloadProbe

    @Before
    fun setUp() {
        downloadManager = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.getSystemService(Context.DOWNLOAD_SERVICE) } returns downloadManager
        probe = TranslateModelDownloadProbe(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun rows(vararg rows: Array<Any>) {
        val cursor = MatrixCursor(
            arrayOf(
                DownloadManager.COLUMN_ID,
                DownloadManager.COLUMN_URI,
                DownloadManager.COLUMN_STATUS,
                DownloadManager.COLUMN_REASON,
                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR,
                DownloadManager.COLUMN_TOTAL_SIZE_BYTES,
            )
        )
        rows.forEach { cursor.addRow(it) }
        every { downloadManager.query(any()) } returns cursor
    }

    private fun modelRow(id: Long, soFar: Long, total: Long, status: Int = DownloadManager.STATUS_RUNNING) =
        arrayOf<Any>(id, "https://dl.google.com/translate/offline/zh_v1.zip", status, 0, soFar, total)

    // --- T24b ---------------------------------------------------------------

    @Test
    fun `T24b a foreign download row is ignored and the ML Kit row is returned`() {
        rows(
            arrayOf<Any>(1L, "https://example.com/some-app-file.bin", DownloadManager.STATUS_RUNNING, 0, 10L, 20L),
            modelRow(id = 2L, soFar = 500L, total = 1_000L),
        )

        val snapshot = probe.snapshot()

        assertEquals(2L, snapshot?.downloadId)
        assertEquals(500L, snapshot?.bytesSoFar)
    }

    @Test
    fun `T24b a result set with no ML Kit row yields null`() {
        rows(arrayOf<Any>(1L, "https://example.com/other.bin", DownloadManager.STATUS_RUNNING, 0, 10L, 20L))

        assertNull(probe.snapshot())
    }

    @Test
    fun `T24b the redirector host matches too`() {
        rows(
            arrayOf<Any>(
                7L,
                "https://redirector.gvt1.com/edgedl/translate/offline/zh_v1.zip",
                DownloadManager.STATUS_PENDING,
                0,
                0L,
                0L,
            )
        )

        assertEquals(7L, probe.snapshot()?.downloadId)
    }

    // --- T53 ----------------------------------------------------------------

    @Test
    fun `T53 the newest row wins, and every column is read into the snapshot`() {
        rows(
            modelRow(id = 3L, soFar = 100L, total = 1_000L),
            modelRow(id = 9L, soFar = 900L, total = 1_000L, status = DownloadManager.STATUS_FAILED),
        )

        val snapshot = probe.snapshot()

        // Row ids are monotonic, so the largest is the most recent attempt.
        assertEquals(9L, snapshot?.downloadId)
        assertEquals(DownloadManager.STATUS_FAILED, snapshot?.status)
        assertEquals(900L, snapshot?.bytesSoFar)
        assertEquals(1_000L, snapshot?.totalBytes)
    }

    @Test
    fun `an empty result set yields null rather than throwing`() {
        rows()

        assertNull(probe.snapshot())
    }

    @Test
    fun `a query failure degrades to null`() {
        every { downloadManager.query(any()) } throws IllegalStateException("provider gone")

        assertNull(probe.snapshot())
    }

    // --- availability -------------------------------------------------------

    @Test
    fun `isAvailable reports a disabled or missing DownloadManager`() {
        assertTrue(probe.isAvailable())

        val noService = mockk<Context>(relaxed = true)
        every { noService.getSystemService(Context.DOWNLOAD_SERVICE) } returns null

        assertFalse(TranslateModelDownloadProbe(noService).isAvailable())
    }

    @Test
    fun `remove forwards to DownloadManager and swallows its failures`() {
        probe.remove(42L)
        verify(exactly = 1) { downloadManager.remove(42L) }

        every { downloadManager.remove(any()) } throws SecurityException("not ours")
        probe.remove(42L)
    }
}
