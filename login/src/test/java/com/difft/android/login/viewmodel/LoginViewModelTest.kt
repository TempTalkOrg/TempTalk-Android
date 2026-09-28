package com.difft.android.login.viewmodel

import androidx.lifecycle.Observer
import com.difft.android.base.migration.MigrationImportLauncher
import com.difft.android.base.user.UserManager
import com.difft.android.login.data.AccountData
import com.difft.android.login.data.CodeResponse
import com.difft.android.login.data.EmailVerifyData
import com.difft.android.login.repo.LoginRepo
import com.difft.android.login.viewmodel.LoginViewModel.Companion.DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE
import com.difft.android.network.BaseResponse
import com.difft.android.network.viewmodel.Resource
import com.difft.android.network.viewmodel.Status
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.builders.buildUserData
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * The Next-button chain's state contract (issue #1208): code verification runs straight on into
 * `verifyInvitationCode` / `signIn` in the same coroutine, so the screen treats the pair as one
 * operation and drives its non-cancelable wait dialog off `signInLiveData` alone. What that screen
 * relies on, and what these tests pin, is that `signInLiveData` opens with LOADING before the code
 * request goes out, stays there across the handover between legs, and reaches a terminal state as
 * its last value on every path out of either leg — a path that only settled the code LiveData would
 * leave the modal up with nothing left to release it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoginViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private val loginRepo: LoginRepo = mockk()
    private val userManager: UserManager = mockk()

    // Existing-account (nextStep != 0) now routes through onLoginExistingAccount, which asks the
    // migration launcher before falling through to the same signIn these tests pin. Not a fresh
    // install target / no pending restore reproduces the pre-migration byte-identical fallback —
    // these tests are about signIn's own behavior, not the migration prompt.
    private val migrationImportLauncher: MigrationImportLauncher = mockk {
        every { pendingRestoreSignIn() } returns null
        every { isImportOffered() } returns true
        every { isFreshInstallTarget() } returns false
    }

    private lateinit var viewModel: LoginViewModel

    private val signInStates = mutableListOf<Resource<AccountData>>()
    private val observer = Observer<Resource<AccountData>> { signInStates += it }

    @Before
    fun setUp() {
        viewModel = LoginViewModel().apply {
            loginRepo = this@LoginViewModelTest.loginRepo
            userManager = this@LoginViewModelTest.userManager
            encryptionDataManager = mockk(relaxed = true)
            httpClient = mockk(relaxed = true)
            favoriteWriteRepo = mockk(relaxed = true)
            migrationImportLauncher = this@LoginViewModelTest.migrationImportLauncher
            pendingLoginStore = mockk(relaxed = true)
        }
        // No account on file, so the different-account guard passes unless a test says otherwise.
        every { userManager.getUserData() } returns null
        viewModel.signInLiveData.observeForever(observer)
    }

    @After
    fun tearDown() {
        viewModel.signInLiveData.removeObserver(observer)
        clearAllMocks()
    }

    // ---------- repo failure: the code request itself fails ----------

    @Test
    fun `a rejected phone code ends the sign-in stream in error`() {
        coEvery { loginRepo.verifyPhoneCode(PHONE, CODE) } returns failed("code expired")

        viewModel.verifyPhoneCodeWithLogin(PHONE, CODE)

        assertChain("code expired")
    }

    @Test
    fun `a rejected email code ends the sign-in stream in error`() {
        coEvery { loginRepo.verifyEmailCode(EMAIL, CODE) } returns failed("code expired")

        viewModel.verifyEmailCodeWithLogin(EMAIL, CODE)

        assertChain("code expired")
    }

    @Test
    fun `a phone code request that throws ends the sign-in stream in error`() {
        coEvery { loginRepo.verifyPhoneCode(PHONE, CODE) } throws IllegalStateException("offline")

        viewModel.verifyPhoneCodeWithLogin(PHONE, CODE)

        assertChain("offline")
    }

    @Test
    fun `an email code request that throws ends the sign-in stream in error`() {
        coEvery { loginRepo.verifyEmailCode(EMAIL, CODE) } throws IllegalStateException("offline")

        viewModel.verifyEmailCodeWithLogin(EMAIL, CODE)

        assertChain("offline")
    }

    // ---------- repo success, nextStep == 0: on into verifyInvitationCode ----------

    @Test
    fun `a verified phone code whose invitation is rejected ends the sign-in stream in error`() {
        coEvery { loginRepo.verifyPhoneCode(PHONE, CODE) } returns verified(nextStep = 0)
        coEvery { loginRepo.verifyInvitationCode(INVITATION_CODE) } returns failed("invitation revoked")

        viewModel.verifyPhoneCodeWithLogin(PHONE, CODE)

        assertChain("invitation revoked")
    }

    @Test
    fun `a verified email code whose invitation is rejected ends the sign-in stream in error`() {
        coEvery { loginRepo.verifyEmailCode(EMAIL, CODE) } returns verified(nextStep = 0)
        coEvery { loginRepo.verifyInvitationCode(INVITATION_CODE) } returns failed("invitation revoked")

        viewModel.verifyEmailCodeWithLogin(EMAIL, CODE)

        assertChain("invitation revoked")
    }

    /** The invitation leg hands over to `signIn`, whose own guards are terminal states too. */
    @Test
    fun `a verified phone code whose invitation belongs to another account ends in the different-account error`() {
        every { userManager.getUserData() } returns buildUserData(account = "someone-else")
        coEvery { loginRepo.verifyPhoneCode(PHONE, CODE) } returns verified(nextStep = 0)
        coEvery { loginRepo.verifyInvitationCode(INVITATION_CODE) } returns BaseResponse(
            ver = 1,
            status = 0,
            reason = null,
            data = CodeResponse(inviter = null, account = ACCOUNT, vcode = "vcode")
        )

        viewModel.verifyPhoneCodeWithLogin(PHONE, CODE)

        assertDifferentAccountChain()
    }

    // ---------- repo success, nextStep != 0: straight on into signIn ----------

    @Test
    fun `a verified phone code for another account ends in the different-account error`() {
        every { userManager.getUserData() } returns buildUserData(account = "someone-else")
        coEvery { loginRepo.verifyPhoneCode(PHONE, CODE) } returns verified(nextStep = 1)

        viewModel.verifyPhoneCodeWithLogin(PHONE, CODE)

        assertDifferentAccountChain()
    }

    @Test
    fun `a verified email code for another account ends in the different-account error`() {
        every { userManager.getUserData() } returns buildUserData(account = "someone-else")
        coEvery { loginRepo.verifyEmailCode(EMAIL, CODE) } returns verified(nextStep = 1)

        viewModel.verifyEmailCodeWithLogin(EMAIL, CODE)

        assertDifferentAccountChain()
    }

    // ---------- the migration prompt breaks the chain in two ----------

    /**
     * The prompt is the one outcome that is neither success nor failure: sign-in is held and the user
     * decides. Each half is then its own operation, so the leg the user's choice starts has to open
     * the chain again — the screen raises its modal on that state, and a leg that found the chain
     * "already open" would run the whole sign-in with no modal at all.
     */
    @Test
    fun `sign-in after the migration prompt opens the chain again`() {
        every { migrationImportLauncher.isFreshInstallTarget() } returns true
        coEvery { loginRepo.verifyPhoneCode(PHONE, CODE) } returns verifiedWithToken()

        viewModel.verifyPhoneCodeWithLogin(PHONE, CODE)

        // The code leg ended in the prompt: it neither signed in nor settled the chain.
        val pending = viewModel.migrationPromptLiveData.value
        assertNotNull(pending, "a fresh-install target must be prompted, not signed in")
        val afterPrompt = signInStates.size

        // The user's choice starts the next leg. A different account on file settles it immediately,
        // so this pins the OPENING without standing up a sign-in.
        every { userManager.getUserData() } returns buildUserData(account = "someone-else")
        viewModel.continueSignInAfterPrompt(pending, restored = false)

        assertEquals(
            Status.LOADING,
            signInStates.getOrNull(afterPrompt)?.status,
            "the leg after the prompt must re-open the chain, got ${signInStates.map { it.status }}",
        )
    }

    // ---------- helpers ----------

    /** The chain's shape, then the error it settled on. */
    private fun assertChain(message: String) {
        assertChainShape()
        assertEquals(message, signInStates.last().exception?.message)
    }

    private fun assertDifferentAccountChain() {
        assertChainShape()
        assertEquals(DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE, signInStates.last().exception?.errorCode)
    }

    /**
     * LOADING throughout, then exactly one terminal state, at the end: the screen shows its modal
     * on LOADING and releases it on the terminal state, so a terminal state anywhere but last would
     * release the modal while the chain is still running. A duplicate LOADING is harmless in
     * itself — the handle's show is a no-op while its own dialog is up — and only matters once
     * another screen has evicted the dialog, which is why the shape is stated as one LOADING run.
     */
    private fun assertChainShape() {
        val statuses = signInStates.map { it.status }
        assertEquals(
            listOf(Status.LOADING),
            statuses.dropLast(1).distinct(),
            "the chain must be loading until it settles, got $statuses"
        )
        assertNotEquals(Status.LOADING, statuses.lastOrNull(), "the chain must settle, got $statuses")
    }

    /** The transfer reply: a token instead of a code, `nextStep = 0` and no invitation code beside it. */
    private fun verifiedWithToken(): BaseResponse<EmailVerifyData> {
        val plain = verified(nextStep = 1)
        return plain.copy(
            data = plain.data!!.copy(
                nextStep = 0,
                invitationCode = null,
                verificationCode = null,
                transferable = 1,
                tokens = mapOf(LoginViewModel.TOKEN_LOGIN to "tok-1"),
            ),
        )
    }

    private fun verified(nextStep: Int): BaseResponse<EmailVerifyData> = BaseResponse(
        ver = 1,
        status = 0,
        reason = null,
        data = EmailVerifyData(
            status = 0,
            nextStep = nextStep,
            invitationCode = INVITATION_CODE,
            account = ACCOUNT,
            verificationCode = "vcode",
            requirePin = false,
            transferable = 0,
            requirePasscode = false,
            passcodeSalt = null,
            screenLockTimeout = 0
        )
    )

    private fun <T> failed(reason: String): BaseResponse<T> =
        BaseResponse(ver = 1, status = 3, reason = reason, data = null)

    private companion object {
        const val PHONE = "+8613000000000"
        const val EMAIL = "user@temptalk.org"
        const val CODE = "123456"
        const val ACCOUNT = "account-under-test"
        const val INVITATION_CODE = "invitation-code"
    }
}
