package com.difft.android.migration.v2.restore

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.migration.crypto.BackupCryptoImpl
import com.difft.android.migration.model.MigrationError
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.model.RestorePhase
import com.difft.android.migration.v2.container.AttachmentByteSource
import com.difft.android.migration.v2.container.ContainerTotals
import com.difft.android.migration.v2.container.ContainerReader
import com.difft.android.migration.v2.container.ContainerReaderImpl
import com.difft.android.migration.v2.container.ContainerWriterImpl
import com.difft.android.migration.v2.container.FileArchiveSink
import com.difft.android.migration.v2.model.ContentKind
import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.base.utils.Base64
import com.difft.android.migration.v2.model.NeutralConversationLocal
import com.difft.android.migration.v2.model.NeutralIdentity
import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.NeutralMessage
import com.difft.android.migration.v2.model.NeutralRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * I3 — [RestoreOrchestrator.restoreFromStream] (the P2P no-disk REAL-import seam). The streaming twin
 * of [RestoreOrchestratorTest]: a REAL [ContainerWriterImpl]-produced container is fed forward-only
 * through [ContainerReaderImpl.readStreaming] (the genuine verify-before-COMMIT crypto), while only
 * the native-WCDB seams (`StagingDatabase`, `PromoteStep`) are mocked via the orchestrator's injectable
 * factories — identical strategy to the file-path test.
 *
 * Guards proven (data-safety-critical, design §6/§7/§3.4):
 *  - (a) happy path → records into staging, promote runs, [RestoreResult.ReadyForSignIn];
 *  - (b) tamper / truncation → readStreaming throws → staging discarded → promote NEVER runs;
 *  - (c) net-install guard: a non-fresh receiver → rejected, promote NEVER runs (no staging opened);
 *  - (d) account mismatch (fail-fast early-manifest hook) → rejected, promote NEVER runs;
 *  - (e) TOCTOU: device becomes non-fresh BETWEEN the early check and the pre-promote re-check →
 *    rejected at the re-check, staging discarded, promote NEVER runs (live DB not clobbered).
 *
 * The on-device real-staging-WCDB + cold-open-after-promote remains the A6 manual matrix (same as the
 * file path); see [RestoreOrchestratorTest]'s TODO(A6 on-device).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RestoreOrchestratorStreamingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private val crypto = BackupCryptoImpl()

    // P2P keys the envelope with the ephemeral transferKey in the passphrase position (design §3.3).
    private val transferKey = "ephemeral-transfer-key-256bit-material-b64=="
    private val account = "+12025550100"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric reports 0 available bytes for an unregistered volume, which the restore's free-space
        // guard would correctly read as a full disk. Give it room so these cases exercise what they mean to.
        ShadowStatFs.registerStats(context.filesDir, ROOMY_BLOCKS, ROOMY_BLOCKS, ROOMY_BLOCKS)
    }

    @After
    fun resetPlatformState() {
        ShadowStatFs.reset()
        PromoteMarkerStore(context).clear()
    }

    // ── (a) happy path → staging import + promote + ReadyForSignIn ───────────────────────────────

    @Test
    fun `streaming restore imports promotes and returns ReadyForSignIn`() = runTest {
        val bytes = writeRealContainer(account)
        val paths = tmpPaths()
        val flagStore = RestoreFlagStore(context).also { it.clear() }
        val staging = relaxedStaging()
        val promote = okPromote()
        val orchestrator = orchestratorWith(ContainerReaderImpl(crypto))

        val result = orchestrator.restoreFromStream(
            source = ByteArrayInputStream(bytes),
            transferKey = transferKey,
            currentVerifyAccount = account,
            paths = paths,
            flagStore = flagStore,
            promoteStep = promote,
            stagingFactory = { staging },
            isFreshTarget = { true },
        )

        assertEquals(RestoreResult.ReadyForSignIn, result, "actual result was: $result")
        // Promote ran with the staging destinations — the streaming engine ran end-to-end.
        coVerify(exactly = 1) {
            promote.promote(paths.stagingDbPath, any(), paths.attachmentsStagingDir, paths.realDbPath, paths.realAttachmentDir)
        }
        // Flag reached SIGNING_IN — "data on disk, ready for A5/I4". Orchestrator did NOT signIn/restart.
        assertEquals(RestorePhase.SIGNING_IN, flagStore.currentPhase())
        // Records actually flowed into the staging target (at least the message + attachment inserts).
        coVerify(atLeast = 1) { staging.insertMessage(any()) }
        coVerify(exactly = 1) { staging.close() }
    }

    @Test
    fun `a failure after promote is committed-unconfirmed and keeps the SIGNING_IN flag`() = runTest {
        val bytes = writeRealContainer(account)
        val paths = tmpPaths()
        // Promote succeeds; the very next step (advancing the flag) writes SIGNING_IN and then fails,
        // the shape of any post-commit error. The data is on disk, so the flag must survive cleanup —
        // and no verdict may be published in either direction, so the failure escapes as
        // CommittedUnconfirmed rather than being reported as an ordinary Failed result.
        val flagStore = spyk(RestoreFlagStore(context).also { it.clear() })
        every { flagStore.advance(RestorePhase.SIGNING_IN) } answers {
            callOriginal()
            throw IllegalStateException("post-promote failure")
        }
        val promote = okPromote()
        // The promote is mocked, so stand in the marker a real one leaves while its own tail (P3/P4)
        // is unfinished: that marker is what the cold-start resume reads, and its presence is what
        // keeps the flag from being cleared as a last resort.
        PromoteMarkerStore(context).write(
            PromoteMarker.create(
                stagingDbPath = paths.stagingDbPath,
                stagingKey = null,
                attachmentsStagingDir = paths.attachmentsStagingDir,
                realDbPath = paths.realDbPath,
                realAttachmentDir = paths.realAttachmentDir,
            ),
        )

        assertFailsWith<MigrationException.CommittedUnconfirmed> {
            orchestratorWith(ContainerReaderImpl(crypto)).restoreFromStream(
                source = ByteArrayInputStream(bytes),
                transferKey = transferKey,
                currentVerifyAccount = account,
                paths = paths,
                flagStore = flagStore,
                promoteStep = promote,
                stagingFactory = { relaxedStaging() },
                isFreshTarget = { true },
            )
        }

        coVerify(exactly = 1) { promote.promote(any(), any(), any(), any(), any()) }
        assertEquals(RestorePhase.SIGNING_IN, RestoreFlagStore(context).currentPhase(), "promoted data must keep its flag")
    }

    // ── identity: checked before promote, so a bad key never reaches sign-in ──────────────────────

    @Test
    fun `a bare 32-byte public key is normalized and the restore completes`() = runTest {
        // What iOS shipped on 2026-09-09. Unnormalized it decodes to "bad key type" at sign-in's second
        // request — after the first has already revoked the old device.
        val bytes = writeRealContainer(account, identity = identity(publicKey = ByteArray(32) { 0xAA.toByte() }))

        val result = restore(bytes) {}

        assertEquals(RestoreResult.ReadyForSignIn, result)
    }

    @Test
    fun `an unusable identity is rejected before promote and clears the flag`() = runTest {
        val bytes = writeRealContainer(account, identity = identity(publicKey = ByteArray(20)))
        val paths = tmpPaths()
        val flagStore = RestoreFlagStore(context).also { it.clear() }
        val promote = okPromote()

        val result = orchestratorWith(ContainerReaderImpl(crypto)).restoreFromStream(
            source = ByteArrayInputStream(bytes),
            transferKey = transferKey,
            currentVerifyAccount = account,
            paths = paths,
            flagStore = flagStore,
            promoteStep = promote,
            stagingFactory = { relaxedStaging() },
            isFreshTarget = { true },
        )

        assertEquals(RestoreResult.Rejected(MigrationError.IdentityInvalid), result)
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
        // Nothing was committed, so nothing is left for a later login to resume.
        assertEquals(null, flagStore.currentPhase())
    }

    private fun identity(publicKey: ByteArray) = NeutralIdentity(
        aciPublicKey = Base64.encodeBytes(publicKey),
        aciPrivateKey = Base64.encodeBytes(ByteArray(32) { it.toByte() }),
    )

    // ── progress: the manifest totals drive a bar that ends at exactly 1.0 ─────────────────────────

    @Test
    fun `progress follows the manifest totals and ends at exactly one`() = runTest {
        // Three records, no attachment bytes — the record term alone must carry the bar to 1.0.
        val bytes = writeRealContainer(account, totals = ContainerTotals(recordCount = 3, attachmentCount = 0, attachmentBytes = 0))
        val seen = mutableListOf<Float>()

        val result = restore(bytes) { seen += it }

        assertEquals(RestoreResult.ReadyForSignIn, result)
        assertEquals(1f, seen.last(), "the last report must be exactly 1.0, got $seen")
        assertEquals(true, seen.zipWithNext().all { (a, b) -> b >= a }, "progress must be monotonic: $seen")
    }

    @Test
    fun `a container without totals reports no progress rather than a wrong one`() = runTest {
        val bytes = writeRealContainer(account) // no totals → embedded counts are zero
        val seen = mutableListOf<Float>()

        val result = restore(bytes) { seen += it }

        assertEquals(RestoreResult.ReadyForSignIn, result)
        assertEquals(emptyList<Float>(), seen)
    }

    private suspend fun restore(bytes: ByteArray, onProgress: (Float) -> Unit): RestoreResult {
        val paths = tmpPaths()
        val flagStore = RestoreFlagStore(context).also { it.clear() }
        return orchestratorWith(ContainerReaderImpl(crypto)).restoreFromStream(
            source = ByteArrayInputStream(bytes),
            transferKey = transferKey,
            currentVerifyAccount = account,
            paths = paths,
            flagStore = flagStore,
            promoteStep = okPromote(),
            stagingFactory = { relaxedStaging() },
            isFreshTarget = { true },
            onProgress = onProgress,
        )
    }

    // ── (b) tamper → readStreaming throws → staging discarded → NO promote ───────────────────────

    @Test
    fun `tampered stream throws, discards staging, and never promotes`() = runTest {
        val bytes = writeRealContainer(account)
        // Flip a byte inside the trailing HMAC region — the body decodes but the end-of-stream MAC fails.
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 0x5A).toByte()
        val paths = tmpPaths()
        val staging = relaxedStaging()
        val promote = mockk<PromoteStep>()
        val orchestrator = orchestratorWith(ContainerReaderImpl(crypto))

        val result = orchestrator.restoreFromStream(
            source = ByteArrayInputStream(bytes),
            transferKey = transferKey,
            currentVerifyAccount = account,
            paths = paths,
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = promote,
            stagingFactory = { staging },
            isFreshTarget = { true },
        )

        // HMAC mismatch → Failed; staging discarded; promote NEVER reached (net-install device stays empty).
        assertIs<RestoreResult.Failed>(result)
        coVerify(exactly = 1) { staging.discard() }
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `truncated stream throws, discards staging, and never promotes`() = runTest {
        val bytes = writeRealContainer(account)
        val truncated = bytes.copyOf(bytes.size - 40) // cut into the trailing HMAC / last frames
        val paths = tmpPaths()
        val staging = relaxedStaging()
        val promote = mockk<PromoteStep>()
        val orchestrator = orchestratorWith(ContainerReaderImpl(crypto))

        val result = orchestrator.restoreFromStream(
            source = ByteArrayInputStream(truncated),
            transferKey = transferKey,
            currentVerifyAccount = account,
            paths = paths,
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = promote,
            stagingFactory = { staging },
            isFreshTarget = { true },
        )

        assertIs<RestoreResult.Failed>(result)
        coVerify(exactly = 1) { staging.discard() }
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
    }

    // ── (b2) I5 T-SM2: cancel mid-stream (other-device-cancel / FGS teardown) → discard, no promote ─

    @Test
    fun `cancel mid-stream discards staging and never promotes`() = runBlocking<Unit> {
        // The container body trickles through a BLOCKING source whose final bytes never arrive — the
        // import is suspended inside readStreaming when the FGS cancels serviceScope (user cancel /
        // other-device abort, I5 §5). The orchestrator's CancellationException branch must discard
        // staging and NEVER promote (net-install device stays empty).
        val bytes = writeRealContainer(account)
        val paths = tmpPaths()
        val staging = relaxedStaging()
        val promote = mockk<PromoteStep>()
        val orchestrator = orchestratorWith(ContainerReaderImpl(crypto))

        // Drip-feed the container slowly so the import is still mid-stream when we cancel.
        val source = SlowDripStream(bytes)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch {
            orchestrator.restoreFromStream(
                source = source,
                transferKey = transferKey,
                currentVerifyAccount = account,
                paths = paths,
                flagStore = RestoreFlagStore(context).also { it.clear() },
                promoteStep = promote,
                stagingFactory = { staging },
                isFreshTarget = { true },
            )
        }

        // Let the import open staging + start consuming, then cancel (the FGS serviceScope cancel).
        source.awaitReading()
        job.cancelAndJoin()

        coVerify(exactly = 1) { staging.discard() }
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
        scope.cancel()
    }

    // ── (c) net-install guard: non-fresh receiver → rejected, no staging, no promote ─────────────

    @Test
    fun `non-fresh target is rejected before any staging or promote`() = runTest {
        val bytes = writeRealContainer(account)
        val promote = mockk<PromoteStep>()
        val orchestrator = orchestratorWith(ContainerReaderImpl(crypto))

        val result = orchestrator.restoreFromStream(
            source = ByteArrayInputStream(bytes),
            transferKey = transferKey,
            currentVerifyAccount = account,
            paths = tmpPaths(),
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = promote,
            stagingFactory = { error("staging must not be opened when the net-install guard fails") },
            isFreshTarget = { false }, // device already has an established account → reject
        )

        // Rejected with TargetNotEmpty (unified with the file path); promote NEVER reached, no staging opened.
        assertEquals(MigrationError.TargetNotEmpty, assertIs<RestoreResult.Rejected>(result).error)
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
    }

    // ── (e) TOCTOU: device becomes non-fresh BETWEEN the early check and the pre-promote re-check ─

    @Test
    fun `device becomes non-fresh during import is rejected at the pre-promote re-check`() = runTest {
        val bytes = writeRealContainer(account)
        val paths = tmpPaths()
        val staging = relaxedStaging()
        val promote = mockk<PromoteStep>()
        val orchestrator = orchestratorWith(ContainerReaderImpl(crypto))

        // fresh on the EARLY guard (import proceeds), non-fresh on the pre-promote re-check (a concurrent
        // signIn/websocket established an account during the stream import). The pre-promote re-check must
        // catch it → discard staging, NO promote, Rejected(TargetNotEmpty) — never clobber the now-live DB.
        var calls = 0
        val freshThenNot = { calls++ == 0 }

        val result = orchestrator.restoreFromStream(
            source = ByteArrayInputStream(bytes),
            transferKey = transferKey,
            currentVerifyAccount = account,
            paths = paths,
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = promote,
            stagingFactory = { staging },
            isFreshTarget = freshThenNot,
        )

        assertEquals(MigrationError.TargetNotEmpty, assertIs<RestoreResult.Rejected>(result).error)
        // Import ran (records flowed into staging), but the pre-promote re-check discarded it before promote.
        coVerify(atLeast = 1) { staging.insertMessage(any()) }
        coVerify(exactly = 1) { staging.discard() }
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
    }

    // ── (d) account mismatch (fail-fast early-manifest hook) → rejected, no promote ──────────────

    @Test
    fun `account mismatch fails fast on the manifest and never promotes`() = runTest {
        // Container was exported by a DIFFERENT account than the receiver's logging-in account.
        val bytes = writeRealContainer("+19998887777")
        val paths = tmpPaths()
        val staging = relaxedStaging()
        val promote = mockk<PromoteStep>()
        val orchestrator = orchestratorWith(ContainerReaderImpl(crypto))

        val result = orchestrator.restoreFromStream(
            source = ByteArrayInputStream(bytes),
            transferKey = transferKey,
            currentVerifyAccount = account, // != manifest sourceAccount → fail-fast reject
            paths = paths,
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = promote,
            stagingFactory = { staging },
            isFreshTarget = { true },
        )

        // Account mismatch throws from the early-manifest hook → Failed; staging discarded; NO promote.
        assertIs<RestoreResult.Failed>(result)
        coVerify(exactly = 1) { staging.discard() }
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
        // The mismatch fired on the manifest (first frame) — no message row was ever inserted into staging.
        coVerify(exactly = 0) { staging.insertMessage(any()) }
    }

    // ── (f) an attachment whose frame is empty → row demoted, no staging file ────────────────────

    @Test
    fun `an empty attachment frame demotes the row and leaves no staging file`() = runTest {
        val bytes = writeRealContainer(account, attachments = listOf(attachment("att-empty", "m-1")))
        val paths = tmpPaths()
        val flagStore = RestoreFlagStore(context).also { it.clear() }
        val staging = relaxedStaging()
        val orchestrator = orchestratorWith(ContainerReaderImpl(crypto))

        val result = orchestrator.restoreFromStream(
            source = ByteArrayInputStream(bytes),
            transferKey = transferKey,
            currentVerifyAccount = account,
            paths = paths,
            flagStore = flagStore,
            promoteStep = okPromote(),
            stagingFactory = { staging },
            isFreshTarget = { true },
        )

        assertEquals(RestoreResult.ReadyForSignIn, result, "actual result was: $result")
        // The row was inserted claiming bytes before the frame arrived; an empty frame has to take
        // that claim back, or the app opens a file that was never written.
        coVerify(exactly = 1) { staging.markAttachmentBytesAbsent("att-empty") }
        assertTrue(
            File(paths.attachmentsStagingDir).walkTopDown().none { it.isFile },
            "an empty frame must not leave a 0-byte staging file",
        )
    }

    // ── T39: running out of space before the commit boundary ────────────────────────────────────

    @Test
    fun `out of space during the staging import discards staging and never promotes`() = runTest {
        val bytes = writeRealContainer(account)
        val paths = tmpPaths()
        val flagStore = RestoreFlagStore(context).also { it.clear() }
        val promote = mockk<PromoteStep>()
        val staging = relaxedStaging()
        // Running out of room mid-import, raised where the spool and the attachment writer raise it.
        // ContainerReader passes a MigrationException through untouched — which is exactly why
        // OutOfSpace is one — so the classification survives to the orchestrator's catch.
        val reader = mockk<ContainerReader>()
        coEvery { reader.readStreaming(any(), any(), any(), any(), any()) } throws
            MigrationException.OutOfSpace("receive spool write ran out of space")

        val result = RestoreOrchestrator(
            context, reader, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        ).restoreFromStream(
            source = ByteArrayInputStream(bytes),
            transferKey = transferKey,
            currentVerifyAccount = account,
            paths = paths,
            flagStore = flagStore,
            promoteStep = promote,
            stagingFactory = { staging },
            isFreshTarget = { true },
        )

        assertEquals(MigrationError.DiskSpaceInsufficient, assertIs<RestoreResult.Failed>(result).error)
        coVerify(exactly = 1) { staging.discard() }
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
        assertTrue(!PromoteMarkerStore(context).exists(), "nothing was committed, so no promote marker exists")
        assertTrue(!flagStore.exists(), "a pre-commit failure leaves no RESTORING residue")
    }

    // ── helpers (mirror RestoreOrchestratorTest's seams) ─────────────────────────────────────────

    private fun orchestratorWith(reader: ContainerReaderImpl) =
        RestoreOrchestrator(context, reader, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    private fun okPromote(): PromoteStep = mockk<PromoteStep>().also {
        coEvery { it.promote(any(), any(), any(), any(), any()) } returns Unit
    }

    private fun relaxedStaging(): StagingDatabase = mockk<StagingDatabase>(relaxed = true).also { st ->
        every { st.stagingKey } returns ByteArray(48) { 1 }
        // Execute the importer's transaction block so the buffered inserts (insertMessage / upsertRoom …)
        // actually fire against the mock — otherwise a relaxed runInTransaction would silently no-op the
        // block and the insert verifications below could never observe a row.
        every { st.runInTransaction(any()) } answers { (firstArg<() -> Unit>()).invoke() }
    }

    private fun tmpPaths(): RestorePaths {
        val work = tmp.newFolder("restore-work-${System.nanoTime()}")
        return RestorePaths(
            realDbPath = File(tmp.newFolder("real-${System.nanoTime()}"), "tt_wcdb_database.db").absolutePath,
            realAttachmentDir = File(work, "real-att").absolutePath,
            stagingDbPath = File(work, "staging.db").absolutePath,
            attachmentsStagingDir = File(work, "att-staging").absolutePath,
            containerWorkFile = File(work, "container.ttmig").absolutePath,
            spoolDir = File(work, "spool").absolutePath,
        )
    }

    /**
     * Write a REAL encrypted container (one conversation + two text messages) keyed by [transferKey],
     * returning its raw bytes (what the P2P upload body carries). The manifest's `sourceAccount` is
     * [sourceAccount].
     *
     * Text-only by default: this suite proves the orchestrator WIRING + the data-safety GUARDS over a
     * genuine verify-before-COMMIT crypto stream (HMAC / truncation / account / net-install / promote-
     * gating). The attachment-byte-to-canonical-staging-file write is byte-for-byte the SAME code the
     * file path's `importToStaging` uses (`stagingAttachmentFile` + the importer placement), exercised
     * by `RestoreOrchestratorTest`/`ContainerReaderStreamingTest` and the on-device A6 matrix; it
     * needs the real `FilePathManager`/native FS and is out of scope for this pure-JVM guard suite.
     *
     * [attachments] are appended after the messages, and the byte source returns nothing for every
     * ref, so each one travels as the empty frame the writer emits when it has no bytes — the shape
     * the "bytes never landed" guard reads.
     */
    private suspend fun writeRealContainer(
        sourceAccount: String,
        totals: ContainerTotals? = null,
        identity: NeutralIdentity? = null,
        attachments: List<NeutralRecord.Attachment> = emptyList(),
    ): ByteArray {
        val out = tmp.newFile("stream-${System.nanoTime()}.ttmig")
        val records = listOfNotNull<NeutralRecord>(
            identity?.let { NeutralRecord.Identity(it) },
            NeutralRecord.ConversationLocal(NeutralConversationLocal(conversationRef = "room-1")),
            msg("m-1", "hello stream", emptyList()),
            msg("m-2", "after first", emptyList()),
        ) + attachments
        ContainerWriterImpl(crypto).write(
            records = records.asFlow(),
            sink = FileArchiveSink(out),
            passphrase = transferKey,
            attachmentBytes = AttachmentByteSource { null },
            sourceAccount = sourceAccount,
            sourceAppVersion = "test-1.0",
            totals = totals,
        )
        return out.readBytes()
    }

    /**
     * An [InputStream] that drip-feeds [all] one small chunk per read, sleeping [DRIP_MS] between
     * chunks, and signals [awaitReading] once the first chunk is served. The slow drip keeps the import
     * coroutine busy in the record loop long enough for the test to cancel its job; the orchestrator's
     * per-record `currentCoroutineContext().ensureActive()` then throws `CancellationException` after the
     * next chunk returns — exactly the FGS-teardown / other-device-cancel path (I5 §5). The stream never
     * completes before the cancel lands, so HMAC is never reached → import unwinds via the cancel branch.
     */
    private class SlowDripStream(private val all: ByteArray) : java.io.InputStream() {
        private var pos = 0
        private val readingLatch = java.util.concurrent.CountDownLatch(1)

        fun awaitReading() = readingLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= all.size) return -1
            // Sleep BEFORE serving so the consuming coroutine spends real time between records; a cancel
            // landing during this sleep is observed by the next ensureActive() after this read returns.
            Thread.sleep(DRIP_MS)
            val n = minOf(len, CHUNK, all.size - pos)
            System.arraycopy(all, pos, b, off, n)
            pos += n
            readingLatch.countDown()
            return n
        }

        companion object {
            private const val CHUNK = 8
            private const val DRIP_MS = 25L
        }
    }

    private fun msg(
        id: String,
        body: String?,
        attachmentRefs: List<String> = emptyList(),
        kind: ContentKind = ContentKind.TEXT,
    ): NeutralRecord.Message = NeutralRecord.Message(
        NeutralMessage(
            stableId = id, conversationRef = "room-1", sender = "u-self",             direction = MessageDirection.INCOMING, body = body, sentAt = 1000,
            serverAt = 1010, receivedAt = 1020, expireTimer = 0,
            contentKind = kind, systemType = null, confidential = false,
            attachmentRefs = attachmentRefs,
            // An explicit exception anchor, which the ladder expansion must leave untouched.
            expireStartedAt = 1030,
        )
    )

    private fun attachment(id: String, messageRef: String): NeutralRecord.Attachment =
        NeutralRecord.Attachment(
            NeutralAttachment(
                stableId = id, messageRef = messageRef, contentType = "image/jpeg", size = 10,
                width = null, height = null, durationMs = null, fileName = "a.jpg",
            )
        )

    private companion object {
        /** ~4 GiB of 4 KiB blocks: more than any of these fixtures could ever need. */
        const val ROOMY_BLOCKS = 1_000_000
    }
}
