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










@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoginViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private val loginRepo: LoginRepo = mockk()
    private val userManager: UserManager = mockk()

    
    
    
    
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
        
        every { userManager.getUserData() } returns null
        viewModel.signInLiveData.observeForever(observer)
    }

    @After
    fun tearDown() {
        viewModel.signInLiveData.removeObserver(observer)
        clearAllMocks()
    }

    

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

    

    





    @Test
    fun `sign-in after the migration prompt opens the chain again`() {
        every { migrationImportLauncher.isFreshInstallTarget() } returns true
        coEvery { loginRepo.verifyPhoneCode(PHONE, CODE) } returns verifiedWithToken()

        viewModel.verifyPhoneCodeWithLogin(PHONE, CODE)

        
        val pending = viewModel.migrationPromptLiveData.value
        assertNotNull(pending, "a fresh-install target must be prompted, not signed in")
        val afterPrompt = signInStates.size

        
        
        every { userManager.getUserData() } returns buildUserData(account = "someone-else")
        viewModel.continueSignInAfterPrompt(pending, restored = false)

        assertEquals(
            Status.LOADING,
            signInStates.getOrNull(afterPrompt)?.status,
            "the leg after the prompt must re-open the chain, got ${signInStates.map { it.status }}",
        )
    }

    

    
    private fun assertChain(message: String) {
        assertChainShape()
        assertEquals(message, signInStates.last().exception?.message)
    }

    private fun assertDifferentAccountChain() {
        assertChainShape()
        assertEquals(DIFFERENT_ACCOUNT_LOGIN_ERROR_CODE, signInStates.last().exception?.errorCode)
    }

    






    private fun assertChainShape() {
        val statuses = signInStates.map { it.status }
        assertEquals(
            listOf(Status.LOADING),
            statuses.dropLast(1).distinct(),
            "the chain must be loading until it settles, got $statuses"
        )
        assertNotEquals(Status.LOADING, statuses.lastOrNull(), "the chain must settle, got $statuses")
    }

    
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
