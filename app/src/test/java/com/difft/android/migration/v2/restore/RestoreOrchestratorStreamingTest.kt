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
import com.difft.android.migration.v2.model.NeutralPendingEnvelope
import com.difft.android.migration.v2.model.NeutralRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.slot
import org.difft.app.database.models.PendingMessageModelNew
import kotlin.test.assertContentEquals
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







@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RestoreOrchestratorStreamingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private val crypto = BackupCryptoImpl()

    
    private val transferKey = "ephemeral-transfer-key-256bit-material-b64=="
    private val account = "+12025550100"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        
        ShadowStatFs.registerStats(context.filesDir, ROOMY_BLOCKS, ROOMY_BLOCKS, ROOMY_BLOCKS)
    }

    @After
    fun resetPlatformState() {
        ShadowStatFs.reset()
        PromoteMarkerStore(context).clear()
    }

    

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
        
        coVerify(exactly = 1) {
            promote.promote(paths.stagingDbPath, any(), paths.attachmentsStagingDir, paths.realDbPath, paths.realAttachmentDir)
        }
        
        assertEquals(RestorePhase.SIGNING_IN, flagStore.currentPhase())
        
        coVerify(atLeast = 1) { staging.insertMessage(any()) }
        coVerify(exactly = 1) { staging.close() }
    }

    @Test
    fun `a failure after promote is committed-unconfirmed and keeps the SIGNING_IN flag`() = runTest {
        val bytes = writeRealContainer(account)
        val paths = tmpPaths()
        
        
        
        
        val flagStore = spyk(RestoreFlagStore(context).also { it.clear() })
        every { flagStore.advance(RestorePhase.SIGNING_IN) } answers {
            callOriginal()
            throw IllegalStateException("post-promote failure")
        }
        val promote = okPromote()
        
        
        
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

    

    @Test
    fun `a bare 32-byte public key is normalized and the restore completes`() = runTest {
        
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
        
        assertEquals(null, flagStore.currentPhase())
    }

    private fun identity(publicKey: ByteArray) = NeutralIdentity(
        aciPublicKey = Base64.encodeBytes(publicKey),
        aciPrivateKey = Base64.encodeBytes(ByteArray(32) { it.toByte() }),
    )

    

    @Test
    fun `progress follows the manifest totals and ends at exactly one`() = runTest {
        
        val bytes = writeRealContainer(account, totals = ContainerTotals(recordCount = 3, attachmentCount = 0, attachmentBytes = 0))
        val seen = mutableListOf<Float>()

        val result = restore(bytes) { seen += it }

        assertEquals(RestoreResult.ReadyForSignIn, result)
        assertEquals(1f, seen.last(), "the last report must be exactly 1.0, got $seen")
        assertEquals(true, seen.zipWithNext().all { (a, b) -> b >= a }, "progress must be monotonic: $seen")
    }

    @Test
    fun `a container without totals reports no progress rather than a wrong one`() = runTest {
        val bytes = writeRealContainer(account) 
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

    

    @Test
    fun `tampered stream throws, discards staging, and never promotes`() = runTest {
        val bytes = writeRealContainer(account)
        
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

        
        assertIs<RestoreResult.Failed>(result)
        coVerify(exactly = 1) { staging.discard() }
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `truncated stream throws, discards staging, and never promotes`() = runTest {
        val bytes = writeRealContainer(account)
        val truncated = bytes.copyOf(bytes.size - 40) 
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

    

    @Test
    fun `cancel mid-stream discards staging and never promotes`() = runBlocking<Unit> {
        
        
        
        
        val bytes = writeRealContainer(account)
        val paths = tmpPaths()
        val staging = relaxedStaging()
        val promote = mockk<PromoteStep>()
        val orchestrator = orchestratorWith(ContainerReaderImpl(crypto))

        
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

        
        source.awaitReading()
        job.cancelAndJoin()

        coVerify(exactly = 1) { staging.discard() }
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
        scope.cancel()
    }

    

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
            isFreshTarget = { false }, 
        )

        
        assertEquals(MigrationError.TargetNotEmpty, assertIs<RestoreResult.Rejected>(result).error)
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
    }

    

    @Test
    fun `device becomes non-fresh during import is rejected at the pre-promote re-check`() = runTest {
        val bytes = writeRealContainer(account)
        val paths = tmpPaths()
        val staging = relaxedStaging()
        val promote = mockk<PromoteStep>()
        val orchestrator = orchestratorWith(ContainerReaderImpl(crypto))

        
        
        
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
        
        coVerify(atLeast = 1) { staging.insertMessage(any()) }
        coVerify(exactly = 1) { staging.discard() }
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
    }

    

    @Test
    fun `account mismatch fails fast on the manifest and never promotes`() = runTest {
        
        val bytes = writeRealContainer("+19998887777")
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

        
        assertIs<RestoreResult.Failed>(result)
        coVerify(exactly = 1) { staging.discard() }
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
        
        coVerify(exactly = 0) { staging.insertMessage(any()) }
    }

    

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
        
        
        coVerify(exactly = 1) { staging.markAttachmentBytesAbsent("att-empty") }
        assertTrue(
            File(paths.attachmentsStagingDir).walkTopDown().none { it.isFile },
            "an empty frame must not leave a 0-byte staging file",
        )
    }

    

    @Test
    fun `out of space during the staging import discards staging and never promotes`() = runTest {
        val bytes = writeRealContainer(account)
        val paths = tmpPaths()
        val flagStore = RestoreFlagStore(context).also { it.clear() }
        val promote = mockk<PromoteStep>()
        val staging = relaxedStaging()
        
        
        
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

    

    private fun pendingEnvelope(text: String = java.util.Base64.getEncoder().encodeToString(byteArrayOf(8, 1))) =
        NeutralRecord.PendingEnvelope(NeutralPendingEnvelope("recall-1", 1010L, text))

    private suspend fun restorePending(staging: StagingDatabase, promote: PromoteStep, pending: NeutralRecord.PendingEnvelope): RestoreResult =
        orchestratorWith(ContainerReaderImpl(crypto)).restoreFromStream(
            source = ByteArrayInputStream(writeRealContainer(account, pending = listOf(pending))),
            transferKey = transferKey,
            currentVerifyAccount = account,
            paths = tmpPaths(),
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = promote,
            stagingFactory = { staging },
            isFreshTarget = { true },
        )

    @Test
    fun `a pending envelope is in the staging file before promote`() = runTest {
        val staging = relaxedStaging()
        val stored = slot<PendingMessageModelNew>()
        every { staging.insertPendingEnvelope(capture(stored)) } returns Unit
        val promote = okPromote()

        val result = restorePending(staging, promote, pendingEnvelope())

        assertEquals(RestoreResult.ReadyForSignIn, result, "actual result was: $result")
        assertEquals("recall-1", stored.captured.messageId)
        assertEquals(1010L, stored.captured.originalMessageTimeStamp)
        assertContentEquals(byteArrayOf(8, 1), stored.captured.messageEnvelopBytes)
        
        coVerifyOrder {
            staging.insertPendingEnvelope(any())
            staging.close()
            promote.promote(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `a pending envelope that cannot be stored fails the import and never promotes`() = runTest {
        val staging = relaxedStaging()
        every { staging.insertPendingEnvelope(any()) } throws IllegalStateException("disk I/O")
        val promote = mockk<PromoteStep>()

        val result = restorePending(staging, promote, pendingEnvelope())

        assertIs<RestoreResult.Failed>(result)
        coVerify(exactly = 1) { staging.discard() }
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a pending envelope that does not decode fails the import and never promotes`() = runTest {
        val staging = relaxedStaging()
        val promote = mockk<PromoteStep>()

        val result = restorePending(staging, promote, pendingEnvelope(text = "%%% not base64 %%%"))

        assertIs<RestoreResult.Failed>(result)
        coVerify(exactly = 0) { staging.insertPendingEnvelope(any()) }
        coVerify(exactly = 1) { staging.discard() }
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
    }

    

    private fun orchestratorWith(reader: ContainerReaderImpl) =
        RestoreOrchestrator(context, reader, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    private fun okPromote(): PromoteStep = mockk<PromoteStep>().also {
        coEvery { it.promote(any(), any(), any(), any(), any()) } returns Unit
    }

    private fun relaxedStaging(): StagingDatabase = mockk<StagingDatabase>(relaxed = true).also { st ->
        every { st.stagingKey } returns ByteArray(48) { 1 }
        
        
        
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

    




    private suspend fun writeRealContainer(
        sourceAccount: String,
        totals: ContainerTotals? = null,
        identity: NeutralIdentity? = null,
        attachments: List<NeutralRecord.Attachment> = emptyList(),
        pending: List<NeutralRecord.PendingEnvelope> = emptyList(),
    ): ByteArray {
        val out = tmp.newFile("stream-${System.nanoTime()}.ttmig")
        val records = pending + listOfNotNull<NeutralRecord>(
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
        
        const val ROOMY_BLOCKS = 1_000_000
    }
}
