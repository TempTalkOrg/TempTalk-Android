package com.difft.android.setting

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.difft.android.R
import com.difft.android.base.log.LogHelper
import com.difft.android.base.log.lumberjack.FileLoggingSetup
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.pane.DetailNavigation
import com.difft.android.base.utils.pane.DetailStackHost
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobmanager.JobManager
import com.difft.android.chat.ui.ChatSelectBottomSheetFragment
import com.difft.android.chat.ui.ChatsContact
import com.difft.android.chat.ui.SelectChatsUtils
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.group.GroupRepo
import com.difft.android.network.responses.GetConversationSetResponseBody
import com.difft.android.setting.data.CheckUpdateResponse
import com.difft.android.setting.repo.SettingRepo
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
import util.FileSystemUtils
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.test.*


@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = com.difft.android.test.FoldableTestApplication_Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutLifecycleIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatchers = TestDispatcherRule()
    @get:Rule(order = 2) val globals = GlobalStaticMockRule()
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @BindValue @JvmField val settings: SettingRepo = mockk()
    @BindValue @JvmField val strictUsers: com.difft.android.base.storage.user.StorageBoundUserManager = mockk(relaxed = true)
    @BindValue @JvmField val selectorBoundary: com.difft.android.chat.ui.SelectChatsUtils = mockk(relaxed = true)
    @BindValue @JvmField val groups: GroupRepo = mockk(relaxed = true)
    @Inject @ChativeHttpClientModule.Chat lateinit var http: ChativeHttpClient
    private lateinit var controller: ActivityController<AboutLifecycleTestActivity>
    private lateinit var folder: File
    private val liveWaits = mutableSetOf<Int>()
    private var token = 0
    private var updateDialogs = 0
    private var bottomDialogs = 0
    private var confirmation: (() -> Unit)? = null
    private var confirmationDismiss: (() -> Unit)? = null

    @Before fun setup() {
        every { com.difft.android.base.utils.application } returns androidx.test.core.app.ApplicationProvider.getApplicationContext<com.difft.android.base.application.ScopeApplication>()
        hilt.inject()
        mockkObject(ComposeDialogManager, ToastUtil, PackageUtil)
        io.mockk.mockkStatic(ComposeDialogManager::class)
        every { PackageUtil.getAppVersionName() } returns "1.0"
        every { PackageUtil.getAppVersionCode() } returns 1
        every { globals.mockGlobalServices.environmentHelper.getChannelName() } returns "official"
        every { globals.mockGlobalServices.environmentHelper.isInsiderChannel() } returns false
        every { ToastUtil.show(any<Int>()) } just Runs
        every { ToastUtil.show(any<String>()) } just Runs
        every { ToastUtil.showLong(any<String>()) } just Runs
        every { ComposeDialogManager.showWait(any(), any(), any(), any(), any(), any()) } answers { (++token).also { liveWaits += it } }
        every { ComposeDialogManager.isWaitShowing(any()) } answers { firstArg<Int>() in liveWaits }
        every { ComposeDialogManager.dismissWait(any<Int>()) } answers { liveWaits -= firstArg<Int>(); Unit }
        every {
            ComposeDialogManager.showMessageDialog(context = any(), title = any(), message = any(), confirmText = any(), cancelText = any(),
                showCancel = any(), cancelable = any(), autoDismiss = any(), confirmButtonColor = any(), cancelButtonColor = any(),
                onConfirm = any(), onCancel = any(), onDismiss = any(), onClosed = any(), content = any())
        } answers { updateDialogs++; mockk<ComposeDialog>(relaxed = true) }
        every {
            ComposeDialogManager.showMessageDialog(context = any(), title = any(), message = any(), confirmText = any(), cancelText = any(),
                showCancel = any(), cancelable = any(), autoDismiss = any(), confirmButtonColor = any(), cancelButtonColor = any(),
                onConfirm = any(), onCancel = any(), onDismiss = any(), layoutId = any(), onViewCreated = any())
        } answers { confirmation = arg(10); confirmationDismiss = arg(12); mockk<ComposeDialog>(relaxed = true) }
        controller = Robolectric.buildActivity(AboutLifecycleTestActivity::class.java).setup()
        folder = File(controller.get().cacheDir, "about-lifecycle-${System.nanoTime()}").apply { mkdirs() }
    }

    @After fun cleanup() {
        controller.pause().stop().destroy()
        folder.deleteRecursively()
        io.mockk.unmockkStatic(ComposeDialogManager::class)
        io.mockk.unmockkStatic(com.difft.android.base.log.lumberjack.L::class)
        io.mockk.unmockkStatic(FileSystemUtils::class)
        io.mockk.unmockkStatic(ApplicationDependencies::class)
        unmockkObject(ComposeDialogManager, ToastUtil, PackageUtil, LogHelper, ChatSelectBottomSheetFragment.Companion,
            AttachmentPathResolver, ApplicationDependencies)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun show(): Pair<AboutFragment, AboutLifecycleHost> {
        val host = AboutLifecycleHost()
        controller.get().supportFragmentManager.beginTransaction().replace(android.R.id.content, host).commitNow()
        val page = AboutFragment()
        host.childFragmentManager.beginTransaction().replace(AboutLifecycleHost.CONTENT, page).commitNow()
        idle()
        return page to host
    }
    private fun clickUpdate() = compose.onNodeWithText(controller.get().getString(R.string.settings_check_new_version)).performClick()
    private fun response() = BaseResponse(1, 0, null, CheckUpdateResponse(true, false, "url", "notes", false, "hash"))
    private fun busy(page: AboutFragment, field: String): Boolean =
        (AboutFragment::class.java.getDeclaredField(field).apply { isAccessible = true }.get(page) as kotlinx.coroutines.flow.StateFlow<*>).value == true
    private fun checkJob(page: AboutFragment): Job = AboutFragment::class.java.getDeclaredField("updateCheck").apply { isAccessible = true }.get(page) as Job

    @Test fun `real About check releases hidden wait and preserves another flow before visible result`() {
        val entered = CompletableDeferred<Unit>()
        val result = CompletableDeferred<BaseResponse<CheckUpdateResponse>>()
        coEvery { settings.checkUpdate(any(), any()) } coAnswers { entered.complete(Unit); result.await() }
        val (page, host) = show()
        clickUpdate()
        compose.waitUntil(5_000) { entered.isCompleted }
        assertTrue(liveWaits.isNotEmpty())
        host.visible(false)
        idle()
        assertTrue(liveWaits.isEmpty())
        val sibling = ComposeDialogManager.showWait(controller.get(), "another flow")
        result.complete(response())
        compose.waitUntil(5_000) { !busy(page, "updateBusy") }
        assertEquals(setOf(sibling), liveWaits)
        assertEquals(0, updateDialogs)
        host.visible(true)
        compose.waitUntil(5_000) { updateDialogs == 1 }
        assertEquals(setOf(sibling), liveWaits)
        verify(exactly = 0) { ComposeDialogManager.dismissWait() }
    }

    @Test fun `destroyed About cancels its check and late response cannot display a dialog or toast`() {
        val entered = CompletableDeferred<Unit>()
        val result = CompletableDeferred<BaseResponse<CheckUpdateResponse>>()
        coEvery { settings.checkUpdate(any(), any()) } coAnswers { withContext(NonCancellable) { entered.complete(Unit); result.await() } }
        val (page, host) = show()
        clickUpdate()
        compose.waitUntil(5_000) { entered.isCompleted }
        val job = checkJob(page)
        host.childFragmentManager.beginTransaction().remove(page).commitNow()
        result.complete(response())
        compose.waitUntil(5_000) { job.isCompleted }
        assertTrue(job.isCancelled)
        assertTrue(liveWaits.isEmpty())
        assertEquals(0, updateDialogs)
        verify(exactly = 0) { ToastUtil.show(any<String>()) }
        verify(exactly = 0) { ToastUtil.showLong(any<String>()) }
    }

    @Test fun `hidden log preparation releases wait and deletes its zip when its submission dialog is dismissed`() {
        val log = File(folder, "first.log").apply { writeText("diagnostics") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val logging = mockk<FileLoggingSetup.DateFiles>()
        every { logging.getAllExistingLogFiles() } answers { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); listOf(log) }
        mockkObject(LogHelper)
        every { LogHelper.FILE_LOGGING_SETUP } returns logging
        every { ComposeDialogManager.showBottomDialog(activity = any(), layoutId = any(), onDismiss = any(), onViewCreated = any()) } answers {
            bottomDialogs++
            val dismissed = arg<() -> Unit>(2)
            var showing = true
            mockk<ComposeDialog> { every { isShowing } answers { showing }; every { dismiss() } answers { if (showing) { showing = false; dismissed() } } }
        }
        val (page, host) = show()
        compose.onNodeWithText(controller.get().getString(R.string.settings_submit_debug_log)).performClick()
        compose.waitUntil(5_000) { entered.count == 0L }
        host.visible(false)
        idle()
        assertTrue(liveWaits.isEmpty())
        release.countDown()
        compose.waitUntil(5_000) { !busy(page, "prepareBusy") && folder.listFiles().orEmpty().any { it.extension == "zip" } }
        assertEquals(0, bottomDialogs)
        host.visible(true)
        compose.waitUntil(5_000) { bottomDialogs == 1 }
        host.childFragmentManager.beginTransaction().remove(page).commitNow()
        compose.waitUntil(5_000) { folder.listFiles().orEmpty().none { it.extension == "zip" } }
    }

    @Test fun `actual archive producer deletes partially written output on IOException`() = runTest {
        val first = File(folder, "first.log").apply { writeText("first") }
        val output = File(folder, "partial.zip")
        assertFailsWith<IOException> { zipAboutLogFiles(listOf(first, File(folder, "missing.log")), output) }
        assertFalse(output.exists())
        assertTrue(first.exists())
    }

    @Test fun `actual archive producer removes its output on coroutine cancellation`() = runTest {
        val first = File(folder, "first.log").apply { writeText("first") }
        val output = File(folder, "canceled.zip")
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            zipAboutLogFiles(listOf(first), output)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(output.exists())
        assertTrue(first.exists())
    }

    private fun picker(): ChatSelectBottomSheetFragment {
        val picker = spyk(ChatSelectBottomSheetFragment())
        mockkObject(ChatSelectBottomSheetFragment.Companion)
        every { ChatSelectBottomSheetFragment.newInstance(any(), any()) } returns picker
        every { picker.show(any<androidx.fragment.app.FragmentManager>(), any()) } just Runs
        every { picker.dismiss() } answers { picker.onSelected?.invoke(null); Unit }
        return picker
    }
    private fun sharing() = SelectChatsUtils(mockk(relaxed = true), mockk(relaxed = true))
    private fun target() = ChatsContact("receiver", "Receiver", null, "R", false, SelectChatsUtils.ITEM_TYPE_CONTACT, null)

    @Test fun `owned share keeps its source through a cancelled confirmation and releases it once when the picker closes`() {
        val picker = picker()
        val source = File(folder, "owned.zip").apply { writeText("owned") }
        val unrelated = File(folder, "unrelated.zip").apply { writeText("keep") }
        var released = 0
        sharing().showChatSelectAndSendDialog(controller.get(), "log", file = source, onFileReleased = { released++; source.delete() })
        picker.onSelected?.invoke(target())
        requireNotNull(confirmationDismiss).invoke()
        
        assertEquals(0, released)
        assertTrue(source.exists())
        verify(exactly = 0) { picker.dismiss() }
        picker.onSelected?.invoke(null)
        assertEquals(1, released)
        assertFalse(source.exists())
        picker.onSelected?.invoke(null)
        assertEquals(1, released)
        assertTrue(unrelated.exists())
    }

    @Test fun `owned share survives a picker close while copy is in flight and releases after send completion`() {
        val picker = picker()
        val source = File(folder, "owned.zip").apply { writeText("owned") }
        val staged = File(folder, "staged.zip")
        val entered = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val released = CompletableDeferred<Unit>()
        mockkObject(AttachmentPathResolver, ApplicationDependencies)
        io.mockk.mockkStatic(FileSystemUtils::class)
        every { AttachmentPathResolver.stagingFileFor(any(), any()) } returns staged.absolutePath
        every { FileSystemUtils.copy(any<String>(), any<String>()) } answers {
            entered.countDown(); check(releaseCopy.await(5, TimeUnit.SECONDS)); source.copyTo(staged, overwrite = true); true
        }
        val sendErrors = java.util.concurrent.CopyOnWriteArrayList<String>()
        io.mockk.mockkStatic(com.difft.android.base.log.lumberjack.L::class)
        every { com.difft.android.base.log.lumberjack.L.e(any<Throwable>(), any<() -> String>()) } answers {
            sendErrors += firstArg<Throwable>().stackTraceToString(); Unit
        }
        every { com.difft.android.base.log.lumberjack.L.e(any<() -> String>()) } answers {
            sendErrors += firstArg<() -> String>().invoke(); Unit
        }
        val jobs = mockk<JobManager>(relaxed = true)
        io.mockk.mockkStatic(ApplicationDependencies::class)
        every { ApplicationDependencies.getJobManager() } returns jobs
        coEvery { http.httpService.fetchGetConversationSet(any()) } returns BaseResponse(1, 0, null, GetConversationSetResponseBody(emptyList()))
        val sharing = sharing().apply {
            messageArchiveManager = mockk { coEvery { getMessageArchiveTime(any(), any()) } returns 0L }
            pushTextSendJobFactory = mockk(relaxed = true)
        }
        sharing.showChatSelectAndSendDialog(controller.get(), "log", file = source, onFileReleased = { source.delete(); released.complete(Unit) })
        picker.onSelected?.invoke(target())
        requireNotNull(confirmation).invoke()
        compose.waitUntil(5_000) { entered.count == 0L }
        picker.onSelected?.invoke(null)
        assertTrue(source.exists())
        assertFalse(released.isCompleted)
        releaseCopy.countDown()
        compose.waitUntil(5_000) { released.isCompleted }
        assertTrue(staged.exists())
        assertFalse(source.exists())
        assertTrue(sendErrors.isEmpty(), sendErrors.joinToString("\n"))
        verify(atLeast = 1) { jobs.add(any()) }
    }

    @Test fun `owned share failure before copy releases its source after failed send finishes`() {
        val picker = picker()
        val source = File(folder, "owned.zip").apply { writeText("owned") }
        val released = CompletableDeferred<Unit>()
        mockkObject(AttachmentPathResolver)
        io.mockk.mockkStatic(FileSystemUtils::class)
        every { AttachmentPathResolver.stagingFileFor(any(), any()) } returns File(folder, "unused").absolutePath
        every { FileSystemUtils.copy(any<String>(), any<String>()) } throws IOException("copy failed")
        sharing().showChatSelectAndSendDialog(controller.get(), "log", file = source, onFileReleased = { source.delete(); released.complete(Unit) })
        picker.onSelected?.invoke(target())
        requireNotNull(confirmation).invoke()
        compose.waitUntil(5_000) { released.isCompleted }
        assertFalse(source.exists())
    }
}

@AndroidEntryPoint
class AboutLifecycleTestActivity : FragmentActivity()

class AboutLifecycleHost : Fragment(), DetailStackHost {
    companion object { const val CONTENT = 0x012301ff }
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    override val detailNavigation: DetailNavigation = mockk(relaxed = true)
    override fun detailBackOwnerFor(fragment: Fragment): LifecycleOwner = owner
    fun visible(value: Boolean) { owner.registry.currentState = if (value) Lifecycle.State.STARTED else Lifecycle.State.CREATED }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { id = CONTENT; visible(true) }
    override fun onDestroyView() { owner.registry.currentState = Lifecycle.State.DESTROYED; super.onDestroyView() }
}
