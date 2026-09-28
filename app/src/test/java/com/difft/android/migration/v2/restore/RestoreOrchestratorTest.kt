package com.difft.android.migration.v2.restore

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.Base64
import com.difft.android.migration.crypto.BackupCryptoImpl
import com.difft.android.migration.model.MigrationError
import com.difft.android.migration.model.RestorePhase
import com.difft.android.migration.v2.container.AttachmentByteSource
import com.difft.android.migration.v2.container.ContainerFormat
import com.difft.android.migration.v2.container.ContainerManifest
import com.difft.android.migration.v2.container.ContainerReader
import com.difft.android.migration.v2.container.ContainerReaderImpl
import com.difft.android.migration.v2.container.ContainerWriterImpl
import com.difft.android.migration.v2.container.FileArchiveSink
import com.difft.android.migration.v2.container.FileArchiveSource
import com.difft.android.migration.v2.model.ContentKind
import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.migration.v2.model.NeutralIdentity
import com.difft.android.migration.v2.model.NeutralMessage
import com.difft.android.migration.v2.model.NeutralRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the A4 [RestoreOrchestrator] (c5 — design §3 T0..T9, validation chain §7.5).
 *
 * Two reader strategies, by what each test must exercise:
 *
 *  - **Crypto-dependent rejects** (wrong passphrase, tampered HMAC, truncated/corrupt) use the REAL
 *    [ContainerReaderImpl] over a REAL encrypted `.ttmig` written by the REAL [ContainerWriterImpl]
 *    (AES-CTR + HMAC + PBKDF2) — so the verify-before-trust path is genuine production code.
 *  - **Manifest-logic rejects** (cross-MAJOR version, account mismatch) + the happy path use a
 *    FAKE [ContainerReader] returning a chosen [ContainerManifest], because the writer hard-codes
 *    [ContainerFormat.FORMAT_VERSION] (you cannot forge a 2.0 manifest through it) and these checks
 *    operate purely on manifest fields, not crypto.
 *
 * Only the native-WCDB parts are mocked: [StagingDatabase] (its ctor opens a cipher WCDB) via the
 * stagingFactory seam, and [PromoteStep] (key install + file moves are unit-tested in
 * `PromoteStepTest`). The real promote-to-cold-open path is on-device:
 *
 *   // TODO(A6 on-device): verify the orchestrator's import writes a real staging WCDB and the
 *   //   promoted DB cold-opens with the installed key. Needs a real device + native WCDB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RestoreOrchestratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private val crypto = BackupCryptoImpl()

    private val passphrase = "correct-horse-battery-staple"
    private val account = "+12025550100"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ── happy path → ReadyForSignIn (engine runs; NO signIn / restart) ─────────────────────────

    @Test
    fun `valid backup imports promotes and returns ReadyForSignIn`() = runTest {
        val paths = tmpPaths()
        val flagStore = RestoreFlagStore(context).also { it.clear() }
        val staging = relaxedStaging()
        val promote = okPromote()
        val orchestrator = orchestratorWith(fakeReader(manifest(account, ContainerFormat.FORMAT_VERSION)))

        val result = orchestrator.restore(
            source = FileArchiveSource(dummyFile()),
            passphrase = passphrase,
            currentVerifyAccount = account,
            paths = paths,
            flagStore = flagStore,
            promoteStep = promote,
            stagingFactory = { staging },
            isFreshTarget = { true },
        )

        assertEquals(RestoreResult.ReadyForSignIn, result)
        // Promote WAS invoked with the staging destinations — the engine ran end-to-end.
        coVerify(exactly = 1) {
            promote.promote(paths.stagingDbPath, any(), paths.attachmentsStagingDir, paths.realDbPath, paths.realAttachmentDir)
        }
        // Flag reached SIGNING_IN: "data on disk, ready for A5". Orchestrator did NOT signIn/restart.
        assertEquals(RestorePhase.SIGNING_IN, flagStore.currentPhase())
        coVerify(exactly = 1) { staging.close() }
    }

    @Test
    fun `same-MAJOR newer-MINOR version is accepted (additive-only)`() = runTest {
        val orchestrator = orchestratorWith(fakeReader(manifest(account, "1.9")))
        val result = orchestrator.restore(
            source = FileArchiveSource(dummyFile()),
            passphrase = passphrase,
            currentVerifyAccount = account,
            paths = tmpPaths(),
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = okPromote(),
            stagingFactory = { relaxedStaging() },
            isFreshTarget = { true },
        )
        assertEquals(RestoreResult.ReadyForSignIn, result)
    }

    // ── validation reject: version too new (cross-MAJOR) ───────────────────────────────────────

    @Test
    fun `cross-MAJOR format version is rejected as UnsupportedVersion (no promote)`() = runTest {
        val promote = mockk<PromoteStep>()
        val orchestrator = orchestratorWith(fakeReader(manifest(account, "2.0")))

        val result = orchestrator.restore(
            source = FileArchiveSource(dummyFile()),
            passphrase = passphrase,
            currentVerifyAccount = account,
            paths = tmpPaths(),
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = promote,
            stagingFactory = { error("staging must not be created on a validation reject") },
        )

        // A NEWER MAJOR surfaces as VersionTooNew (upgrade-guidance UX, contract §7.7); the generic
        // UnsupportedVersion is reserved for older/unparseable versions.
        assertEquals(MigrationError.VersionTooNew, assertIs<RestoreResult.Rejected>(result).error)
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
    }

    // ── validation reject: account mismatch ────────────────────────────────────────────────────

    @Test
    fun `account mismatch is rejected as AccountMismatch (no promote)`() = runTest {
        val promote = mockk<PromoteStep>()
        val orchestrator = orchestratorWith(fakeReader(manifest("+19998887777", ContainerFormat.FORMAT_VERSION)))

        val result = orchestrator.restore(
            source = FileArchiveSource(dummyFile()),
            passphrase = passphrase,
            currentVerifyAccount = account, // different from the manifest source account
            paths = tmpPaths(),
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = promote,
            stagingFactory = { error("staging must not be created on a validation reject") },
        )

        assertEquals(MigrationError.AccountMismatch, assertIs<RestoreResult.Rejected>(result).error)
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
    }

    // ── validation reject: wrong passphrase (REAL crypto) ──────────────────────────────────────

    @Test
    fun `wrong passphrase is rejected as PassphraseInvalid (real crypto, no promote)`() = runTest {
        val container = writeRealContainer(account)
        val promote = mockk<PromoteStep>()
        val orchestrator = orchestratorWith(ContainerReaderImpl(crypto))

        val result = orchestrator.restore(
            source = FileArchiveSource(container),
            passphrase = "WRONG-passphrase",
            currentVerifyAccount = account,
            paths = tmpPaths(),
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = promote,
            stagingFactory = { error("staging must not be created on a validation reject") },
        )

        assertEquals(MigrationError.PassphraseInvalid, assertIs<RestoreResult.Rejected>(result).error)
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
    }

    // ── validation reject: corrupt HMAC (tampered bytes, REAL crypto) ──────────────────────────

    @Test
    fun `tampered container is rejected (real HMAC mismatch path)`() = runTest {
        val container = writeRealContainer(account)
        val bytes = container.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x5A).toByte() // flip a ciphertext byte
        container.writeBytes(bytes)
        val orchestrator = orchestratorWith(ContainerReaderImpl(crypto))

        val result = orchestrator.restore(
            source = FileArchiveSource(container),
            passphrase = passphrase,
            currentVerifyAccount = account,
            paths = tmpPaths(),
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = mockk(),
            stagingFactory = { error("staging must not be created on a validation reject") },
        )

        // §7.5 cannot distinguish tamper from wrong passphrase → PassphraseInvalid (re-pick covers it).
        assertEquals(MigrationError.PassphraseInvalid, assertIs<RestoreResult.Rejected>(result).error)
    }

    // ── validation reject: truncated/corrupt structure (REAL crypto) ───────────────────────────

    @Test
    fun `truncated container is rejected as FileCorrupted (real crypto)`() = runTest {
        val container = writeRealContainer(account)
        container.writeBytes(container.readBytes().copyOf(20)) // header-only → too short
        val orchestrator = orchestratorWith(ContainerReaderImpl(crypto))

        val result = orchestrator.restore(
            source = FileArchiveSource(container),
            passphrase = passphrase,
            currentVerifyAccount = account,
            paths = tmpPaths(),
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = mockk(),
            stagingFactory = { error("staging must not be created on a validation reject") },
        )

        assertEquals(MigrationError.FileCorrupted, assertIs<RestoreResult.Rejected>(result).error)
    }

    // ── net-install guard: a NON-fresh target (account already established) fails ───────────────

    @Test
    fun `non-fresh target (established account) fails the net-install guard`() = runTest {
        val paths = tmpPaths()
        // A device with an account already established here (incl. after a passive device-kick, which
        // PRESERVES account) is NOT a fresh install → import must be rejected.
        val orchestrator = orchestratorWith(fakeReader(manifest(account, ContainerFormat.FORMAT_VERSION)))

        val result = orchestrator.restore(
            source = FileArchiveSource(dummyFile()),
            passphrase = passphrase,
            currentVerifyAccount = account,
            paths = paths,
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = mockk(),
            stagingFactory = { error("staging must not be created when the net-install guard fails") },
            isFreshTarget = { false }, // account already established → must reject
        )

        // Validation passed, net-install guard failed (not fresh) → VALIDATION reject. Unified with the
        // streaming path: Rejected(TargetNotEmpty) (NOT a thrown Failed) — no DB write, target untouched.
        assertEquals(MigrationError.TargetNotEmpty, assertIs<RestoreResult.Rejected>(result).error)
    }

    @Test
    fun `fresh install target (no account established) passes the net-install guard`() = runTest {
        val paths = tmpPaths()
        // A fresh install / post-uninstall / post-full-logout has no account established → fresh; the
        // guard must accept it and run the full restore to ReadyForSignIn.
        val orchestrator = orchestratorWith(fakeReader(manifest(account, ContainerFormat.FORMAT_VERSION)))

        val result = orchestrator.restore(
            source = FileArchiveSource(dummyFile()),
            passphrase = passphrase,
            currentVerifyAccount = account,
            paths = paths,
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = okPromote(),
            stagingFactory = { relaxedStaging() },
            isFreshTarget = { true }, // no account established → net-install OK
        )

        assertEquals(RestoreResult.ReadyForSignIn, result)
    }

    // ── Fix 5: cleanupOnFailure must not mask the original typed failure ────────────────────────

    @Test
    fun `cleanupOnFailure throwing does not mask the original failure`() = runTest {
        val paths = tmpPaths()
        // The real failure: promote throws a typed RestoreFailed → maps to AtomicWriteFailed.
        val promote = mockk<PromoteStep>().also {
            coEvery { it.promote(any(), any(), any(), any(), any()) } throws
                com.difft.android.migration.model.MigrationException.RestoreFailed("import/promote boom")
        }
        // The SECONDARY failure: cleanup's flagStore.clear() throws the SAME kind of storage error that
        // caused the original failure. Before Fix 5 this propagated and crashed the coroutine, losing
        // the typed RestoreResult.Failed. After Fix 5 it is swallowed (L.w) and the original is returned.
        val flagStore = mockk<RestoreFlagStore>(relaxed = true).also {
            every { it.clear() } throws RuntimeException("storage gone during cleanup")
        }
        val orchestrator = orchestratorWith(fakeReader(manifest(account, ContainerFormat.FORMAT_VERSION)))

        val result = orchestrator.restore(
            source = FileArchiveSource(dummyFile()),
            passphrase = passphrase,
            currentVerifyAccount = account,
            paths = paths,
            flagStore = flagStore,
            promoteStep = promote,
            stagingFactory = { relaxedStaging() },
            isFreshTarget = { true },
        )

        // Original typed failure is preserved (NOT the secondary cleanup RuntimeException, NOT a crash).
        assertEquals(MigrationError.AtomicWriteFailed, assertIs<RestoreResult.Failed>(result).error)
    }

    // ── Fix 8: blank contentRef is rejected with a clear typed error ────────────────────────────

    @Test
    fun `blank contentRef attachment gets a generated name and the restore still completes`() = runTest {
        val paths = tmpPaths()
        // One byte frame with a BLANK contentRef: File(dir, "") would be the dir itself, so the
        // orchestrator substitutes a generated name and the write lands instead of failing the restore.
        var opened: java.io.OutputStream? = null
        val reader = mockk<ContainerReader>().also {
            coEvery { it.peekManifest(any(), any()) } returns manifest(account, ContainerFormat.FORMAT_VERSION)
            coEvery { it.read(any(), any(), any(), any()) } answers {
                val target = arg<com.difft.android.migration.v2.container.AttachmentByteTarget>(2)
                opened = target.openForRef("   ", 1L)?.also { out -> out.write(7); out.close() }
                manifest(account, ContainerFormat.FORMAT_VERSION)
            }
        }
        val orchestrator = orchestratorWith(reader)

        val result = orchestrator.restore(
            source = FileArchiveSource(dummyFile()),
            passphrase = passphrase,
            currentVerifyAccount = account,
            paths = paths,
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = mockk(relaxed = true),
            stagingFactory = { relaxedStaging() },
            isFreshTarget = { true },
        )

        assertEquals(RestoreResult.ReadyForSignIn, result)
        assertTrue(opened != null, "the frame was stored under a substitute name, not dropped")
    }

    // ── contract §8.4 — identity install happens BEFORE promote, in ONE atomic write ─────────────

    // Real key SHAPES (contract §8.4): the identity is validated before install, so a placeholder string
    // would now be rejected as unusable rather than installed. Same encoder as the normalizer, so the
    // installed values compare equal to the carried ones.
    private fun carriedIdentity() = NeutralIdentity(
        aciPublicKey = Base64.encodeBytes(byteArrayOf(0x05) + ByteArray(32) { (it + 1).toByte() }),
        aciPrivateKey = Base64.encodeBytes(ByteArray(32) { (it + 101).toByte() }),
        oldAciPublicKey = Base64.encodeBytes(byteArrayOf(0x05) + ByteArray(32) { (it + 51).toByte() }),
        oldAciPrivateKey = Base64.encodeBytes(ByteArray(32) { (it + 151).toByte() }),
        identityKeyGenTime = 1_733_000_000_000L,
        favKey = "ZmF2",
        favKeyId = "fav-id",
        favKeyVersion = 3,
        proxyShareLink = "ytp://config?d=abc",
        proxyEnabled = true,
        proxyProtectCallIp = true,
    )

    @Test
    fun `a carried identity is installed in one atomic update BEFORE promote, preserving genTime`() = runTest {
        val identity = carriedIdentity()
        val paths = tmpPaths()
        val promote = okPromote()
        val userManager = mockk<UserManager>(relaxed = true)
        val config = slot<UserData.() -> Unit>()
        every { userManager.update(any(), capture(config)) } returns Unit

        val orchestrator = RestoreOrchestrator(
            context,
            readerEmitting(NeutralRecord.Identity(identity)),
            mockk(relaxed = true),
            userManager,
            mockk(relaxed = true),
        )

        val result = orchestrator.restore(
            source = FileArchiveSource(dummyFile()),
            passphrase = passphrase,
            currentVerifyAccount = account,
            paths = paths,
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = promote,
            stagingFactory = { relaxedStaging() },
            isFreshTarget = { true },
        )

        assertEquals(RestoreResult.ReadyForSignIn, result)
        // ONE write, and it lands BEFORE promote — the §8.4 ordering red line (publishing an identity
        // that is later overwritten would desynchronize the server from the device).
        coVerify(exactly = 1) { userManager.update(any(), any()) }
        coVerifyOrder {
            userManager.update(any(), any())
            promote.promote(any(), any(), any(), any(), any())
        }

        // The captured mutation writes every carried field — in particular the ORIGINAL genTime.
        // `EncryptionDataManager.updateAciIdentityKey` would have stamped `now` here, which silently
        // reopens/extends the 24h old-key decrypt fallback window.
        val installed = UserData().apply(config.captured)
        assertEquals(identity.aciPublicKey, installed.aciIdentityPublicKey)
        assertEquals(identity.aciPrivateKey, installed.aciIdentityPrivateKey)
        assertEquals(identity.oldAciPublicKey, installed.aciIdentityOldPublicKey)
        assertEquals(identity.oldAciPrivateKey, installed.aciIdentityOldPrivateKey)
        assertEquals(identity.identityKeyGenTime, installed.aciIdentityKeyGenTime, "carried genTime, NOT now")
        assertEquals(identity.favKey, installed.favKey)
        assertEquals(identity.favKeyId, installed.favKeyId)
        assertEquals(identity.favKeyVersion, installed.favKeyVersion)
        assertEquals(identity.proxyShareLink, installed.proxyShareLink)
        assertEquals(true, installed.proxyEnabled)
        assertEquals(true, installed.proxyProtectCallIp)
    }

    @Test
    fun `a container with no identity record performs no identity write`() = runTest {
        val userManager = mockk<UserManager>(relaxed = true)
        val orchestrator = RestoreOrchestrator(
            context,
            fakeReader(manifest(account, ContainerFormat.FORMAT_VERSION)),
            mockk(relaxed = true),
            userManager,
            mockk(relaxed = true),
        )

        val result = orchestrator.restore(
            source = FileArchiveSource(dummyFile()),
            passphrase = passphrase,
            currentVerifyAccount = account,
            paths = tmpPaths(),
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = okPromote(),
            stagingFactory = { relaxedStaging() },
            isFreshTarget = { true },
        )

        assertEquals(RestoreResult.ReadyForSignIn, result)
        // Pre-1.1 container → secure storage is untouched; login generates an identity as before.
        coVerify(exactly = 0) { userManager.update(any(), any()) }
    }

    @Test
    fun `identity is NOT installed when the pre-promote net-install re-check rejects`() = runTest {
        val userManager = mockk<UserManager>(relaxed = true)
        val promote = mockk<PromoteStep>()
        // Fresh at the early guard, non-fresh at the pre-promote re-check (a concurrent signIn wrote
        // the live DB during the import). Nothing may be committed — including the identity.
        var probes = 0
        val orchestrator = RestoreOrchestrator(
            context,
            readerEmitting(NeutralRecord.Identity(carriedIdentity())),
            mockk(relaxed = true),
            userManager,
            mockk(relaxed = true),
        )

        val result = orchestrator.restore(
            source = FileArchiveSource(dummyFile()),
            passphrase = passphrase,
            currentVerifyAccount = account,
            paths = tmpPaths(),
            flagStore = RestoreFlagStore(context).also { it.clear() },
            promoteStep = promote,
            stagingFactory = { relaxedStaging() },
            isFreshTarget = { probes++ == 0 },
        )

        assertEquals(MigrationError.TargetNotEmpty, assertIs<RestoreResult.Rejected>(result).error)
        coVerify(exactly = 0) { userManager.update(any(), any()) }
        coVerify(exactly = 0) { promote.promote(any(), any(), any(), any(), any()) }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** A fake reader that emits [records] to `onRecord` (manifest = the compatible happy-path one). */
    private fun readerEmitting(vararg records: NeutralRecord): ContainerReader {
        val m = manifest(account, ContainerFormat.FORMAT_VERSION)
        return mockk<ContainerReader>().also { r ->
            coEvery { r.peekManifest(any(), any()) } returns m
            coEvery { r.read(any(), any(), any(), any()) } coAnswers {
                @Suppress("UNCHECKED_CAST")
                val onRecord = arg<Any>(3) as suspend (NeutralRecord) -> Unit
                records.forEach { onRecord(it) }
                m
            }
        }
    }

    // A relaxed MigrationImportLauncher mock satisfies the constructor; the net-install guard's
    // default fresh probe is never invoked in these JVM tests — every restore() call passes an
    // explicit `isFreshTarget` seam (the fresh-vs-not logic is what we unit-cover; the real
    // account-empty read is on-device).
    private fun orchestratorWith(reader: ContainerReader) =
        RestoreOrchestrator(context, reader, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    private fun okPromote(): PromoteStep = mockk<PromoteStep>().also {
        coEvery { it.promote(any(), any(), any(), any(), any()) } returns Unit
    }

    private fun relaxedStaging(): StagingDatabase = mockk<StagingDatabase>(relaxed = true).also {
        every { it.stagingKey } returns ByteArray(48) { 1 }
    }

    /** A fake reader: peekManifest returns [m]; read() succeeds with no records. */
    private fun fakeReader(m: ContainerManifest): ContainerReader = mockk<ContainerReader>().also {
        coEvery { it.peekManifest(any(), any()) } returns m
        coEvery { it.read(any(), any(), any(), any()) } returns m
    }

    private fun manifest(sourceAccount: String, formatVersion: String) = ContainerManifest(
        bodyFormatVersion = ContainerFormat.BODY_FORMAT_VERSION,
        encoding = ContainerFormat.ENCODING_JSONL,
        createdAt = 1L,
        recordCount = 0,
        attachmentCount = 0,
        sourceAccount = sourceAccount,
        sourcePlatform = ContainerFormat.SOURCE_PLATFORM_ANDROID,
        sourceAppVersion = "test-1.0",
        formatVersion = formatVersion,
    )

    private fun dummyFile(): File = tmp.newFile("src-${System.nanoTime()}.ttmig").apply { writeBytes(byteArrayOf(0)) }

    private fun tmpPaths(): RestorePaths {
        val work = tmp.newFolder("restore-work-${System.nanoTime()}")
        return RestorePaths(
            realDbPath = File(tmp.newFolder("real-${System.nanoTime()}"), "tt_wcdb_database.db").absolutePath,
            realAttachmentDir = File(work, "real-att").absolutePath,
            stagingDbPath = File(work, "staging.db").absolutePath,
            attachmentsStagingDir = File(work, "att-staging").absolutePath,
            containerWorkFile = File(work, "container.ttmig").absolutePath,
        )
    }

    /** Write a real encrypted container (one text message) with the given manifest source account. */
    private suspend fun writeRealContainer(sourceAccount: String): File {
        val out = tmp.newFile("backup-${System.nanoTime()}.ttmig")
        val records = listOf<NeutralRecord>(
            NeutralRecord.Message(
                NeutralMessage(
                    stableId = "msg-1",
                    conversationRef = "room-1",
                    sender = sourceAccount,
                                        direction = MessageDirection.OUTGOING,
                    body = "hello",
                    sentAt = 1_700_000_000_000L,
                    serverAt = 1_700_000_000_010L,
                    receivedAt = 1_700_000_000_020L,
                    expireTimer = 0,
                    contentKind = ContentKind.TEXT,
                    systemType = null,
                    confidential = false,
                )
            )
        )
        ContainerWriterImpl(crypto).write(
            records = records.asFlow(),
            sink = FileArchiveSink(out),
            passphrase = passphrase,
            attachmentBytes = AttachmentByteSource { null },
            sourceAccount = sourceAccount,
            sourceAppVersion = "test-1.0",
        )
        return out
    }
}
