package com.difft.android.setting

import android.os.Looper
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.DifftClearableInputView
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.common.AvatarUtil
import com.difft.android.login.ContactProfileSettingFragment
import com.difft.android.login.RandomNameUtil
import com.difft.android.login.mvi.ProfileEditViewModel
import com.difft.android.login.repo.ProfileSaveRepository
import com.difft.android.login.repo.ProfileSession
import com.difft.android.setting.repo.AccountMutationRepository
import com.difft.android.test.FoldableTestApplication_Application
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The profile name on the shared input: typing, the field's ✕ and names set from code keep the
 * draft and the Done button in step; IME Done obeys the button's eligibility; the page alone
 * restores the name. Real fragment, view models and saver wiring; only transport is mocked.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = FoldableTestApplication_Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileNameInputIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatchers = TestDispatcherRule()
    @get:Rule(order = 2) val globals = GlobalStaticMockRule()
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @BindValue @JvmField val profile: ProfileSaveRepository = mockk(relaxed = true)
    @BindValue @JvmField val accounts: AccountMutationRepository = mockk(relaxed = true)
    @BindValue @JvmField val groups: com.difft.android.network.group.GroupRepo = mockk(relaxed = true)
    @BindValue @JvmField val strictUsers: StorageBoundUserManager = mockk(relaxed = true)
    @BindValue @JvmField val identity: com.difft.android.setting.repo.IdentityRotationRepository = mockk(relaxed = true)
    @BindValue @JvmField val logoutBoundary: LogoutManagerImpl = mockk(relaxed = true)
    @Inject lateinit var users: UserManager
    private val session = mockk<ProfileSession> { every { accountId } returns "test-user-001" }
    private lateinit var controller: ActivityController<SecuritySettingsTestActivity>
    private var generated: java.io.File? = null

    @Before fun setup() {
        every { com.difft.android.base.utils.application } returns androidx.test.core.app.ApplicationProvider.getApplicationContext<com.difft.android.base.application.ScopeApplication>()
        hilt.inject()
        users.update { account = "test-user-001"; email = "masked"; phoneNumber = null; pattern = null; passcode = "existing" }
        every { profile.captureSession() } returns session
        coEvery { profile.load(any()) } returns null
        coEvery { profile.save(any(), any(), any(), any()) } returns "accepted"
        mockkObject(ToastUtil)
        io.mockk.mockkStatic(ComposeDialogManager::class)
        every { ToastUtil.show(any<Int>()) } just Runs
        every { ToastUtil.show(any<String>()) } just Runs
        controller = Robolectric.buildActivity(SecuritySettingsTestActivity::class.java).setup()
    }

    @After fun cleanup() {
        controller.pause().stop().destroy()
        generated?.delete()
        io.mockk.unmockkStatic(ComposeDialogManager::class)
        io.mockk.unmockkStatic(com.bumptech.glide.Glide::class)
        unmockkObject(ToastUtil, AvatarUtil, RandomNameUtil)
    }

    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }

    private fun prepareGeneratedProfile() {
        io.mockk.mockkStatic(com.bumptech.glide.Glide::class)
        every { com.bumptech.glide.Glide.with(any<View>()) } returns mockk(relaxed = true)
        mockkObject(AvatarUtil, RandomNameUtil)
        generated = java.io.File(controller.get().cacheDir, "profile-generated.png").apply {
            outputStream().use { stream -> android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream) }
        }
        every { AvatarUtil.generateRandomAvatarFile() } returns requireNotNull(generated).absolutePath
        every { RandomNameUtil.getRandomName(any()) } returns "Generated"
    }

    /** The register flow: the page is the shell's own content. */
    private fun registerPage(): ContactProfileSettingFragment {
        prepareGeneratedProfile()
        val page = ContactProfileSettingFragment.newInstance(ContactProfileSettingFragment.FROM_REGISTER)
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, page, "profile").commitNow()
        compose.waitUntil(5_000) { page.name().query.isNotBlank() }
        return page
    }

    /** The Me flow: the page sits in a detail stack. */
    private fun contactPage(): ContactProfileSettingFragment {
        val page = ContactProfileSettingFragment.newInstance(ContactProfileSettingFragment.FROM_CONTACT)
        val host = SecurityFlowHost()
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, host, "host").commitNow()
        host.childFragmentManager.beginTransaction().replace(SecurityFlowHost.CONTENT, page, "page").commitNow()
        idle()
        return page
    }

    private fun Fragment.name(): DifftClearableInputView = requireView().findViewById(com.difft.android.login.R.id.et_name)
    private fun Fragment.done(): View = requireView().findViewById(com.difft.android.login.R.id.btn_done)
    private fun Fragment.draft(): String = ViewModelProvider(this)[ProfileEditViewModel::class.java].state.value.nameDraft
    private fun type(text: String) { compose.onNode(hasSetTextAction()).performTextReplacement(text); idle() }
    private fun tapClear() {
        compose.onNodeWithContentDescription(controller.get().getString(com.difft.android.base.R.string.base_clear_text)).performClick()
        idle()
    }
    private fun imeDone() { compose.onNode(hasSetTextAction()).performImeAction(); idle() }

    @Test fun `typing and the clear icon feed the draft and the done button`() {
        val page = contactPage()
        type("Alice")
        assertEquals("Alice", page.draft())
        assertTrue(page.done().isEnabled)
        tapClear()
        assertEquals("", page.name().query)
        assertEquals("", page.draft())
        assertFalse(page.done().isEnabled)
    }

    @Test fun `a name set from code enables the done button`() {
        val page = registerPage()
        assertEquals("Generated", page.name().query)
        assertTrue(page.done().isEnabled)
    }

    @Test fun `IME done on a cleared name submits nothing in the register flow and once with a name`() {
        val page = registerPage()
        tapClear()
        assertFalse(page.done().isEnabled)
        imeDone()
        coVerify(exactly = 0) { profile.save(any(), any(), any(), any()) }
        type("Bob")
        imeDone()
        coVerify(exactly = 1) { profile.save(any(), "Bob", null, session) }
        coVerify(exactly = 1) { profile.save(any(), any(), any(), any()) }
    }

    @Test fun `IME done on a cleared name submits nothing in the contact flow and once with a name`() {
        val page = contactPage()
        type("Edited")
        tapClear()
        imeDone()
        coVerify(exactly = 0) { profile.save(any(), any(), any(), any()) }
        type("Edited")
        imeDone()
        coVerify(exactly = 1) { profile.save(null, "Edited", null, session) }
        coVerify(exactly = 1) { profile.save(any(), any(), any(), any()) }
    }

    @Test fun `the register flow keeps a typed name across recreation`() {
        registerPage()
        type("Typed")
        controller.recreate()
        idle()
        val restored = controller.get().supportFragmentManager.findFragmentByTag("profile") as ContactProfileSettingFragment
        assertEquals("Typed", restored.name().query)
        assertTrue(restored.done().isEnabled)
    }

    // A stale empty entry for the field's id, seeded apart from the page's own save path, must not
    // replace a newer draft nor feed an empty name back to the editor.
    @Test fun `a stale hierarchy entry for the field neither shows nor dispatches`() {
        val page = contactPage()
        type("Newer")
        val stale = SparseArray<Parcelable>()
        DifftClearableInputView(controller.get()).apply { id = com.difft.android.login.R.id.et_name }.saveHierarchyState(stale)
        page.requireView().restoreHierarchyState(stale)
        idle()
        assertEquals("Newer", page.name().query)
        assertEquals("Newer", page.draft())
        assertTrue(page.done().isEnabled)
    }
}
