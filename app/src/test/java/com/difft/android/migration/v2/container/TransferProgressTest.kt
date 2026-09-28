package com.difft.android.migration.v2.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The progress formula both ends compute. The property that matters is the endpoint: whatever the
 * mix of records and attachment bytes, the bar reaches exactly 1.0 when — and only when — the stream
 * has delivered everything the manifest announced. The old byte-estimate bar could not promise that.
 */
class TransferProgressTest {

    @Test
    fun `unknown totals give no ratio so the bar stays indeterminate`() {
        assertNull(TransferProgress.ratio(3, 100, null))
        assertNull(TransferProgress.ratio(3, 100, ContainerTotals(0, 0, 0)))
        assertNull(ContainerTotals.fromManifest(manifest(recordCount = 0, attachmentBytes = 0)))
    }

    @Test
    fun `reaches exactly one when both terms hit their totals`() {
        val totals = ContainerTotals(recordCount = 175, attachmentCount = 3, attachmentBytes = 5_000_000)

        assertEquals(1f, TransferProgress.ratio(175, 5_000_000, totals))
    }

    @Test
    fun `is monotonic across the record phase and the attachment phase`() {
        val totals = ContainerTotals(recordCount = 10, attachmentCount = 1, attachmentBytes = 40_960)
        val steps = listOf(
            TransferProgress.ratio(0, 0, totals),
            TransferProgress.ratio(5, 0, totals),
            TransferProgress.ratio(10, 0, totals),
            TransferProgress.ratio(10, 20_480, totals),
            TransferProgress.ratio(10, 40_960, totals),
        ).map { it!! }

        assertEquals(0f, steps.first())
        assertEquals(1f, steps.last())
        assertTrue("must never go backwards: $steps", steps.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `a record phase alone still spans the whole bar`() {
        // A text-only history has no attachment bytes; records must carry the bar to 1.0 on their own.
        val totals = ContainerTotals(recordCount = 4, attachmentCount = 0, attachmentBytes = 0)

        assertEquals(0.5f, TransferProgress.ratio(2, 0, totals))
        assertEquals(1f, TransferProgress.ratio(4, 0, totals))
    }

    @Test
    fun `overshoot is clamped rather than exceeding one`() {
        val totals = ContainerTotals(recordCount = 2, attachmentCount = 0, attachmentBytes = 0)

        assertEquals(1f, TransferProgress.ratio(3, 0, totals))
    }

    @Test
    fun `reporter throttles to percent steps but always delivers the end`() {
        val seen = mutableListOf<Float>()
        val reporter = ProgressReporter { seen += it }
        reporter.start(ContainerTotals(recordCount = 1000, attachmentCount = 0, attachmentBytes = 0))

        repeat(1000) { reporter.recordDone() }

        assertEquals(1f, seen.last())
        assertTrue("1000 records must not produce 1000 reports, got ${seen.size}", seen.size <= 101)
        assertTrue("reports must be monotonic", seen.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `reporter says nothing until totals are known`() {
        val seen = mutableListOf<Float>()
        val reporter = ProgressReporter { seen += it }

        reporter.recordDone()
        assertTrue(seen.isEmpty())

        reporter.start(ContainerTotals(recordCount = 1, attachmentCount = 0, attachmentBytes = 0))
        reporter.recordDone()
        assertEquals(listOf(1f), seen)
    }

    private fun manifest(recordCount: Int, attachmentBytes: Long) = ContainerManifest(
        bodyFormatVersion = 1,
        encoding = "jsonl",
        createdAt = 0,
        recordCount = recordCount,
        attachmentCount = 0,
        attachmentBytes = attachmentBytes,
        sourceAccount = "a",
        sourcePlatform = "android",
        sourceAppVersion = "t",
        formatVersion = "1.0",
    )
}
