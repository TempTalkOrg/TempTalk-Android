package com.difft.android.login.viewmodel

import com.difft.android.base.migration.MigrationImportLauncher
import com.difft.android.base.migration.MigrationImportOutcome
import com.difft.android.base.migration.PendingRestoreSignIn
import com.difft.android.base.user.UserManager
import com.difft.android.chat.crypto.IdentityKeyUtil
import com.difft.android.chat.cryptonew.EncryptionDataManager
import com.difft.android.login.data.EmailVerifyData
import com.difft.android.login.entry.PendingLoginStore
import com.difft.android.test.builders.buildUserData
import com.difft.android.login.repo.LoginRepo
import com.difft.android.network.BaseResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import com.difft.android.base.utils.Base64
import kotlin.test.assertTrue

/**
 * A5 (design §6) — `LoginViewModel` migration-prompt decision + restore-path tracking.
 *
 * Focus: the BRANCH at `nextStep == 1` (the migration hook point) and the sign-in continuation —
 * NOT the full network sign-in (libsignal keygen / Retrofit), which is out of scope for a JVM unit
 * test. We assert the observable decision: does the prompt fire, and is the restore path marked?
 *
 * 🔴 Main-path guard: the `nextStep == 0` (register), non-fresh-target, and resume-into-login cases
 * must NEVER emit a prompt — they fall straight into the existing `signIn`, byte-identical to today.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestScopeApplication::class)
class LoginViewModelMigrationTest {

    private val dispatcher = StandardTestDispatcher()

    private val loginRepo: LoginRepo = mockk(relaxed = true)
    private val userManager: UserManager = mockk(relaxed = true)
    private lateinit var launcher: FakeImportLauncher
    private val pendingStore: PendingLoginStore = mockk(relaxed = true)

    private val account = "+12025550100"
    private val vcode = "verify-123"

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        // New device → no existing local account, so checkIsDifferentAccountLogin is false.
        every { userManager.getUserData() } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        pendingRestore: PendingRestoreSignIn? = null,
        freshTarget: Boolean = true,
    ): LoginViewModel {
        launcher = FakeImportLauncher(pending = pendingRestore, freshTarget = freshTarget)
        return LoginViewModel().apply {
            loginRepo = this@LoginViewModelMigrationTest.loginRepo
            userManager = this@LoginViewModelMigrationTest.userManager
            migrationImportLauncher = launcher
            pendingLoginStore = pendingStore
        }
    }

    private fun ok(data: EmailVerifyData) = BaseResponse(ver = 0, status = 0, reason = null, data = data)

    private fun verifyData(nextStep: Int) = EmailVerifyData(
        status = 0,
        nextStep = nextStep,
        invitationCode = "",
        account = account,
        verificationCode = vcode,
        requirePin = false,
        transferable = 0,
        requirePasscode = false,
        passcodeSalt = null,
        screenLockTimeout = 0,
    )

    // ── nextStep == 1, fresh target → prompt emitted (no immediate signIn) ─────────────────────

    @Test
    fun `existing account on fresh target with a transfer on offer emits migration prompt`() = runTest(dispatcher) {
        coEvery { loginRepo.verifyPhoneCode(any(), any()) } returns ok(tokenReply())
        val vm = viewModel()

        vm.verifyPhoneCodeWithLogin(account, "000000")
        dispatcher.scheduler.advanceUntilIdle()

        val prompt = vm.migrationPromptLiveData.value
        assertEquals(account, prompt?.account)
        assertEquals("", prompt?.verificationCode)
        assertEquals("tok-1", prompt?.renewToken)
        // No restore continuation yet → restore path NOT marked.
        assertFalse(vm.signInIsRestorePath)
    }

    // ── no transfer on offer (transferable = 0: the account has no other device) → plain sign-in ──

    @Test
    fun `existing account on fresh target with no transfer on offer signs in without a prompt`() = runTest(dispatcher) {
        coEvery { loginRepo.verifyPhoneCode(any(), any()) } returns ok(verifyData(nextStep = 1))
        val vm = viewModel()

        vm.verifyPhoneCodeWithLogin(account, "000000")
        dispatcher.scheduler.advanceUntilIdle()

        // iOS: `.unsupported` → the reply's own code signs in; nothing to migrate from.
        assertNull(vm.migrationPromptLiveData.value)
        assertFalse(vm.signInIsRestorePath)
        coVerify(exactly = 0) { loginRepo.renewAccount(any()) }
    }

    // ── nextStep == 1, NON-fresh target → NO prompt (used/kicked device → normal signIn) ────────

    @Test
    fun `non-fresh target does not emit prompt`() = runTest(dispatcher) {
        coEvery { loginRepo.verifyPhoneCode(any(), any()) } returns ok(verifyData(nextStep = 1))
        // An account was already established here (e.g. logging in again, or after a passive
        // device-kick that preserves account) → NOT a fresh install → import must be skipped and the
        // flow falls through to the normal signIn, with no prompt.
        val vm = viewModel(freshTarget = false)

        vm.verifyPhoneCodeWithLogin(account, "000000")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.migrationPromptLiveData.value)
        assertFalse(vm.signInIsRestorePath)
    }

    // ── nextStep == 0 (register) → NO prompt ───────────────────────────────────────────────────

    @Test
    fun `register path does not emit prompt`() = runTest(dispatcher) {
        coEvery { loginRepo.verifyEmailCode(any(), any()) } returns ok(verifyData(nextStep = 0))
        // verifyInvitationCode returns no usable data → stops before signIn; we only assert no prompt.
        coEvery { loginRepo.verifyInvitationCode(any()) } returns BaseResponse<com.difft.android.login.data.CodeResponse>(ver = 0, status = -1, reason = "stop", data = null)
        val vm = viewModel()

        vm.verifyEmailCodeWithLogin("a@b.com", "000000")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.migrationPromptLiveData.value)
    }

    // ── resume-into-login: pending SIGNING_IN → skip prompt, mark restore path ──────────────────

    @Test
    fun `pending restore signIn for the same account skips prompt and marks restore path`() = runTest(dispatcher) {
        coEvery { loginRepo.verifyPhoneCode(any(), any()) } returns ok(verifyData(nextStep = 1))
        val vm = viewModel(pendingRestore = PendingRestoreSignIn(account, useInstalledIdentity = true))

        vm.verifyPhoneCodeWithLogin(account, "000000")
        dispatcher.scheduler.advanceUntilIdle()

        // No prompt — data already on disk; continue signIn directly on the restore path.
        assertNull(vm.migrationPromptLiveData.value)
        assertTrue(vm.signInIsRestorePath)
        assertTrue(vm.restoreUseInstalledIdentity)
    }

    @Test
    fun `pending restore whose identity already failed to publish signs in with a fresh identity`() = runTest(dispatcher) {
        coEvery { loginRepo.verifyPhoneCode(any(), any()) } returns ok(verifyData(nextStep = 1))
        val vm = viewModel(pendingRestore = PendingRestoreSignIn(account, useInstalledIdentity = false))

        vm.verifyPhoneCodeWithLogin(account, "000000")
        dispatcher.scheduler.advanceUntilIdle()

        // Still the restore path (data on disk → restart after success), but the installed identity is
        // not retried: one failure must not become a permanent one.
        assertNull(vm.migrationPromptLiveData.value)
        assertTrue(vm.signInIsRestorePath)
        assertFalse(vm.restoreUseInstalledIdentity)
    }

    // ── another account's restored data on disk → different-account login, never signed into ────

    @Test
    fun `pending restore for another account is rejected as a different-account login`() = runTest(dispatcher) {
        coEvery { loginRepo.verifyPhoneCode(any(), any()) } returns ok(verifyData(nextStep = 1))
        // Account A's data was promoted; the local account is still empty (written at sign-in step 3),
        // so the pending-restore record is the only thing that knows whose data is on disk.
        val vm = viewModel(pendingRestore = PendingRestoreSignIn("+12025550199", useInstalledIdentity = true))

        vm.verifyPhoneCodeWithLogin(account, "000000")
        dispatcher.scheduler.advanceUntilIdle()

        // No import prompt for B, no restore path, and the sign-in is refused with the code the screen
        // maps to the different-account dialog (full clear → then B logs in fresh).
        assertNull(vm.migrationPromptLiveData.value)
        assertFalse(vm.signInIsRestorePath)
        assertEquals(
            LoginViewModel.DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE,
            vm.signInLiveData.value?.exception?.errorCode,
        )
    }

    // ── step 2 bookkeeping: a failed publish of the INSTALLED identity is recorded, once ────────

    @Test
    fun `installed identity publish rejected by the server is recorded`() = runTest(dispatcher) {
        val (vm, _) = forkViewModel(hasIdentity = true)
        coEvery { loginRepo.registerPreKeys(any(), any()) } returns BaseResponse(ver = 0, status = -1, reason = "bad key", data = null)

        val result = vm.publishIdentityForSignIn("Basic abc", useInstalledIdentity = true)

        assertEquals(-1, result.status)
        assertEquals(1, launcher.identityPublishFailures, "the failure must be recorded so the next attempt generates")
    }

    @Test
    fun `installed identity that throws while publishing is recorded and the throw propagates`() = runTest(dispatcher) {
        // The 2026-09-09 shape: the installed key would not even decode, so registerPreKeys threw.
        val (vm, edm) = forkViewModel(hasIdentity = true)
        every { edm.getAciIdentityKey() } throws IllegalStateException("Invalid ACI identity key format")

        val thrown = runCatching { vm.publishIdentityForSignIn("Basic abc", useInstalledIdentity = true) }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(1, launcher.identityPublishFailures)
    }

    @Test
    fun `a publish that never reached the server keeps the installed identity`() = runTest(dispatcher) {
        val (vm, _) = forkViewModel(hasIdentity = true)
        coEvery { loginRepo.registerPreKeys(any(), any()) } throws java.io.IOException("timeout")

        val thrown = runCatching { vm.publishIdentityForSignIn("Basic abc", useInstalledIdentity = true) }.exceptionOrNull()

        assertTrue(thrown is java.io.IOException)
        assertEquals(0, launcher.identityPublishFailures, "a transport failure says nothing about the key")
    }

    @Test
    fun `a server error while publishing keeps the installed identity, a client refusal condemns it`() = runTest(dispatcher) {
        val (vm, _) = forkViewModel(hasIdentity = true)

        coEvery { loginRepo.registerPreKeys(any(), any()) } throws com.difft.android.network.NetworkException(503, "unavailable")
        runCatching { vm.publishIdentityForSignIn("Basic abc", useInstalledIdentity = true) }
        assertEquals(0, launcher.identityPublishFailures)

        coEvery { loginRepo.registerPreKeys(any(), any()) } throws com.difft.android.network.NetworkException(400, "bad key")
        runCatching { vm.publishIdentityForSignIn("Basic abc", useInstalledIdentity = true) }
        assertEquals(1, launcher.identityPublishFailures)
    }

    @Test
    fun `a failed publish of a GENERATED identity is not recorded`() = runTest(dispatcher) {
        // Off the installed-identity path there is nothing to fall back from; recording would be noise.
        val (vm, _) = forkViewModel(hasIdentity = true)
        coEvery { loginRepo.registerPreKeys(any(), any()) } returns BaseResponse(ver = 0, status = -1, reason = "x", data = null)

        vm.publishIdentityForSignIn("Basic abc", useInstalledIdentity = false)

        assertEquals(0, launcher.identityPublishFailures)
    }

    @Test
    fun `a successful publish of the installed identity records nothing`() = runTest(dispatcher) {
        val (vm, _) = forkViewModel(hasIdentity = true)
        coEvery { loginRepo.registerPreKeys(any(), any()) } returns BaseResponse(ver = 0, status = 0, reason = null, data = null)

        vm.publishIdentityForSignIn("Basic abc", useInstalledIdentity = true)

        assertEquals(0, launcher.identityPublishFailures)
    }

    // ── supportTransfer: the token path ─────────────────────────────────────────────────────────

    /** The server's real `supportTransfer = 1` reply: `nextStep = 0` and no invitation code alongside the token. */
    private fun tokenReply() = verifyData(nextStep = 0).copy(
        invitationCode = null,
        verificationCode = null,
        transferable = 1,
        tokens = mapOf(LoginViewModel.TOKEN_LOGIN to "tok-1"),
    )

    @Test
    fun `a fresh target asks for a transfer token and the prompt carries it`() = runTest(dispatcher) {
        coEvery { loginRepo.verifyPhoneCode(any(), any()) } returns ok(tokenReply())
        val vm = viewModel()

        vm.verifyPhoneCodeWithLogin(account, "000000")
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { loginRepo.verifyPhoneCode(account, "000000") }
        val pending = vm.migrationPromptLiveData.value
        assertNotNull(pending)
        assertEquals("tok-1", pending!!.renewToken)
        assertEquals("", pending.verificationCode)
        assertEquals(account, pending.account)
        coVerify(exactly = 0) { loginRepo.renewAccount(any()) }
    }

    @Test
    fun `a used device is offered no import but redeems the token only when it signs in`() = runTest(dispatcher) {
        // The same account coming back to a device that already established it (a kicked device):
        // the code verification must not revoke its other device, so the token is still requested;
        // it is redeemed by the sign-in that follows at once, not before.
        coEvery { loginRepo.verifyPhoneCode(any(), any()) } returns ok(tokenReply())
        coEvery { loginRepo.renewAccount("tok-1") } returns ok(verifyData(nextStep = 1))
        every { userManager.getUserData() } returns buildUserData(account)
        val vm = viewModel(freshTarget = false)

        vm.verifyPhoneCodeWithLogin(account, "000000")
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { loginRepo.verifyPhoneCode(account, "000000") }
        assertNull(vm.migrationPromptLiveData.value)
        coVerify(exactly = 1) { loginRepo.renewAccount("tok-1") }
    }

    @Test
    fun `another account's data on this device holds the verified sign-in and asks before redeeming anything`() = runTest(dispatcher) {
        // Android with A's data, B's code verified: B's other device must stay signed in until the user
        // has agreed to wipe A — so the token is held, not redeemed, and the screen gets the replace ask.
        coEvery { loginRepo.verifyPhoneCode(any(), any()) } returns ok(tokenReply())
        every { userManager.getUserData() } returns buildUserData("+12025550199")
        val vm = viewModel(freshTarget = false)

        vm.verifyPhoneCodeWithLogin(account, "000000")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.migrationPromptLiveData.value)
        assertEquals(LoginViewModel.DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE, vm.signInLiveData.value?.exception?.errorCode)
        coVerify(exactly = 0) { loginRepo.renewAccount(any()) }
        coVerify(exactly = 1) { pendingStore.save(match { it.account == account && it.renewToken == "tok-1" }) }
    }

    @Test
    fun `a token reply without an account is redeemed first and then prompts on the plain contract`() = runTest(dispatcher) {
        coEvery { loginRepo.verifyPhoneCode(any(), any()) } returns ok(tokenReply().copy(account = null))
        coEvery { loginRepo.renewAccount("tok-1") } returns ok(verifyData(nextStep = 1))
        val vm = viewModel()

        vm.verifyPhoneCodeWithLogin(account, "000000")
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { loginRepo.renewAccount("tok-1") }
        val pending = vm.migrationPromptLiveData.value
        assertNotNull(pending)
        assertEquals(vcode, pending!!.verificationCode)
        assertEquals(account, pending.account)
        assertEquals(null, pending.renewToken)
    }

    @Test
    fun `continuing after the prompt redeems the token before signing in`() = runTest(dispatcher) {
        coEvery { loginRepo.renewAccount("tok-1") } returns ok(verifyData(nextStep = 1))
        val vm = viewModel()

        vm.continueSignInAfterPrompt(LoginViewModel.PendingSignIn("", account, null, account, renewToken = "tok-1"), restored = true)
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { loginRepo.renewAccount("tok-1") }
        assertTrue(vm.signInIsRestorePath)
    }

    @Test
    fun `a rejected renew ends the chain with an error and no sign-in`() = runTest(dispatcher) {
        coEvery { loginRepo.renewAccount("tok-1") } returns BaseResponse(ver = 0, status = 410, reason = "expired", data = null)
        val vm = viewModel()

        vm.continueSignInAfterPrompt(LoginViewModel.PendingSignIn("", account, null, account, renewToken = "tok-1"), restored = false)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(com.difft.android.network.viewmodel.Status.ERROR, vm.signInLiveData.value?.status)
        coVerify(exactly = 0) { loginRepo.signIn(any(), any(), any(), any(), any()) }
    }

    // ── the failure page's retry only survives a request the server never answered ─────────────

    @Test
    fun `renew that gets no answer posts a transport failure`() = runTest(dispatcher) {
        coEvery { loginRepo.renewAccount(any()) } throws java.io.IOException("timeout")
        val vm = viewModel()

        vm.continueSignInAfterPrompt(
            LoginViewModel.PendingSignIn("", account, null, account, renewToken = "tok"),
            restored = true,
        )
        dispatcher.scheduler.advanceUntilIdle()

        // The token was never consumed, so the same held sign-in may be retried.
        assertTrue(vm.signInLiveData.value?.exception is SignInTransportFailure)
    }

    @Test
    fun `renew rejected by the server is not a transport failure`() = runTest(dispatcher) {
        coEvery { loginRepo.renewAccount(any()) } returns BaseResponse(ver = 0, status = 5, reason = "Invalid Token", data = null)
        val vm = viewModel()

        vm.continueSignInAfterPrompt(
            LoginViewModel.PendingSignIn("", account, null, account, renewToken = "tok"),
            restored = true,
        )
        dispatcher.scheduler.advanceUntilIdle()

        val exception = vm.signInLiveData.value?.exception
        assertNotNull(exception)
        assertFalse(exception is SignInTransportFailure, "a server verdict answers a retry the same way")
        assertEquals(5, exception.errorCode)
    }

    // ── continueSignInAfterPrompt sets the restore-path flag per the outcome ───────────────────

    @Test
    fun `continue after restored marks restore path`() = runTest(dispatcher) {
        val vm = viewModel()
        val pending = LoginViewModel.PendingSignIn(vcode, account, null, account)

        vm.continueSignInAfterPrompt(pending, restored = true)
        assertTrue(vm.signInIsRestorePath)
    }

    @Test
    fun `continue after skip does not mark restore path`() = runTest(dispatcher) {
        val vm = viewModel()
        val pending = LoginViewModel.PendingSignIn(vcode, account, null, account)

        vm.continueSignInAfterPrompt(pending, restored = false)
        assertFalse(vm.signInIsRestorePath)
    }

    // ── Fix B: PendingSignIn survives a Bundle/Parcel round-trip (Activity recreation persistence) ─

    /**
     * Mirrors exactly what `VerifyCodeActivity.onSaveInstanceState` / restore do: write into a
     * [android.os.Bundle], force a real Parcel marshal/unmarshal, and read it back. If `@Parcelize`
     * were ever dropped from `PendingSignIn`, this would fail — guarding the recreation persistence.
     */
    private fun roundTrip(pending: LoginViewModel.PendingSignIn): LoginViewModel.PendingSignIn? {
        val out = android.os.Bundle().apply { putParcelable("k", pending) }
        val parcel = android.os.Parcel.obtain()
        return try {
            out.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val back = android.os.Bundle.CREATOR.createFromParcel(parcel)
            back.classLoader = LoginViewModel.PendingSignIn::class.java.classLoader
            androidx.core.os.BundleCompat.getParcelable(back, "k", LoginViewModel.PendingSignIn::class.java)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `PendingSignIn survives a parcel round-trip`() {
        val original = LoginViewModel.PendingSignIn(vcode, account, email = "a@b.com", phone = account)
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `PendingSignIn with null email and phone survives a parcel round-trip`() {
        val original = LoginViewModel.PendingSignIn(vcode, account, email = null, phone = null)
        assertEquals(original, roundTrip(original))
    }

    // ── contract §8.4 — the install-vs-generate fork inside registerPreKeys ────────────────────
    //
    // `masterKey == the ACI identity private key` and the server holds only the public half, so a
    // regenerated identity is unrecoverable: still-queued messages sealed to the original identity
    // become permanently undecryptable. On the restore path a migration/cold-recovery has already
    // installed the ORIGINAL identity, and this step must PUBLISH it rather than replace it.
    //
    // `IdentityKeyUtil.generateIdentityKeyPair()` is object-mocked because the real implementation
    // reaches libsignal's native library, which is absent from the JVM test classpath.

    /** A stand-in identity pair; libsignal types are mocked (no native lib on the JVM classpath). */
    private fun fakeIdentityPair(publicSerialized: ByteArray, signature: ByteArray): IdentityKeyPair {
        val publicKey = mockk<IdentityKey>().also { every { it.serialize() } returns publicSerialized }
        val privateKey = mockk<ECPrivateKey>().also {
            every { it.calculateSignature(any()) } returns signature
            every { it.serialize() } returns ByteArray(32) { 9 }
        }
        return mockk<IdentityKeyPair>().also {
            every { it.publicKey } returns publicKey
            every { it.privateKey } returns privateKey
        }
    }

    private val installedPair = fakeIdentityPair(ByteArray(33) { 1 }, ByteArray(64) { 2 })
    private val generatedPair = fakeIdentityPair(ByteArray(33) { 3 }, ByteArray(64) { 4 })

    /**
     * Wire a VM whose `encryptionDataManager` reports [hasIdentity], and stub the generator object so
     * the "generate" branch is observable without libsignal's native library.
     */
    private fun forkViewModel(hasIdentity: Boolean): Pair<LoginViewModel, EncryptionDataManager> {
        val edm = mockk<EncryptionDataManager>(relaxed = true).also {
            every { it.hasAciIdentityKey() } returns hasIdentity
            every { it.getAciIdentityKey() } returns installedPair
        }
        mockkObject(IdentityKeyUtil)
        every { IdentityKeyUtil.generateIdentityKeyPair() } returns generatedPair
        val vm = viewModel().apply { encryptionDataManager = dagger.Lazy { edm } }
        return vm to edm
    }

    // ── transferProof: only the restore continuation that publishes the installed identity proves it ──

    @Test
    fun `transferProof is built on the restore path with a token and an installed identity`() {
        val (vm, _) = forkViewModel(hasIdentity = true)

        val proof = vm.transferProofFor(account, "login-token", useInstalledIdentity = true)

        assertEquals(Base64.encodeBytesWithoutPadding(ByteArray(33) { 1 }), proof?.identityKey)
        assertEquals(Base64.encodeBytesWithoutPadding(ByteArray(64) { 2 }), proof?.signature)
    }

    @Test
    fun `no transferProof without a token, off the restore path, or without an installed identity`() {
        val (vm, _) = forkViewModel(hasIdentity = true)
        assertNull(vm.transferProofFor(account, null, useInstalledIdentity = true))
        assertNull(vm.transferProofFor(account, "login-token", useInstalledIdentity = false))

        val (bareVm, _) = forkViewModel(hasIdentity = false)
        assertNull(bareVm.transferProofFor(account, "login-token", useInstalledIdentity = true))
    }

    @After
    fun unmockIdentityKeyUtil() {
        unmockkObject(IdentityKeyUtil)
    }

    @Test
    fun `restore path with an installed identity publishes it and never generates`() = runTest(dispatcher) {
        val (vm, edm) = forkViewModel(hasIdentity = true)

        vm.registerPreKeys("Basic abc", preferInstalledIdentity = true)

        verify(exactly = 0) { IdentityKeyUtil.generateIdentityKeyPair() }
        verify(exactly = 0) { edm.updateAciIdentityKey(any()) }
        verify(exactly = 1) { edm.getAciIdentityKey() }
        // The published key is the INSTALLED one, not a fresh pair. Resolve the expected public key
        // OUTSIDE the verify block — touching a mock inside a `match {}` re-enters the recorder.
        val expectedPublicKey = installedPair.publicKey
        coVerify(exactly = 1) {
            loginRepo.registerPreKeys("Basic abc", match { it.identityKey === expectedPublicKey })
        }
    }

    @Test
    fun `restore path with NO installed identity falls back to generating`() = runTest(dispatcher) {
        // A pre-1.1 container (or a restore whose source had no identity) leaves nothing installed —
        // the fork must fall back rather than fail the login.
        val (vm, edm) = forkViewModel(hasIdentity = false)

        vm.registerPreKeys("Basic abc", preferInstalledIdentity = true)

        verify(exactly = 1) { IdentityKeyUtil.generateIdentityKeyPair() }
        verify(exactly = 1) { edm.updateAciIdentityKey(generatedPair) }
        verify(exactly = 0) { edm.getAciIdentityKey() }
    }

    @Test
    fun `normal login always generates even when an identity is present (main path unchanged)`() = runTest(dispatcher) {
        // 🔴 The main-path red line: outside a restore the flag is false, so the behaviour is exactly
        // what it was before the fork existed — generate + store, never reuse.
        val (vm, edm) = forkViewModel(hasIdentity = true)

        vm.registerPreKeys("Basic abc", preferInstalledIdentity = false)

        verify(exactly = 1) { IdentityKeyUtil.generateIdentityKeyPair() }
        verify(exactly = 1) { edm.updateAciIdentityKey(generatedPair) }
        verify(exactly = 0) { edm.hasAciIdentityKey() }
        verify(exactly = 0) { edm.getAciIdentityKey() }
    }

    /** Fake bridge — no `:app` engine; deterministic resume + fresh-target answers, records marks. */
    private class FakeImportLauncher(
        private val pending: PendingRestoreSignIn?,
        private val freshTarget: Boolean = true,
    ) : MigrationImportLauncher {
        var identityPublishFailures = 0
        override fun pendingRestoreSignIn(): PendingRestoreSignIn? = pending
        override fun markRestoreIdentityPublishFailed() { identityPublishFailures++ }
        override fun isFreshInstallTarget(): Boolean = freshTarget
        override fun markRestoreSignInComplete() = Unit
        override fun isImportOffered(): Boolean = true
        override fun isP2pMigrationOffered(context: android.content.Context): Boolean = false
        override fun createP2pReceiveIntent(
            context: android.content.Context,
            account: String,
            sameDevice: Boolean,
            scanDesktop: Boolean,
            pendingSignIn: android.os.Parcelable?,
        ) = android.content.Intent()
        override fun createSourceChooserIntent(
            context: android.content.Context,
            account: String,
            pendingSignIn: android.os.Parcelable?,
        ) = android.content.Intent()
        override fun parseResult(resultCode: Int, data: android.content.Intent?): MigrationImportOutcome =
            MigrationImportOutcome.Cancelled
    }
}
