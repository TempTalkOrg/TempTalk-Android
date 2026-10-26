package com.difft.android.startup.prompt

import android.app.Application
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.notification.ReadinessIssue
import com.difft.android.base.session.InactiveDeviceNotifier
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.DialogCloseReason
import com.difft.android.setting.UpdateManager
import com.difft.android.setting.data.CheckUpdateResponse
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Lifecycle and lock gating of the IndexActivity prompt host, on a bare FragmentActivity
 * (IndexActivity is an @AndroidEntryPoint and is never booted in tests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IndexStartupPromptHostTest {

    private val current = MutableStateFlow<PromptTicket?>(null)
    private val updateState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Unknown)
    private val securityPending = MutableStateFlow<List<SecurityWarning>>(emptyList())
    private val lockPending = MutableStateFlow(false)

    private val coordinator: StartupPromptCoordinator = mockk(relaxed = true) {
        every { current } returns this@IndexStartupPromptHostTest.current
        coEvery { onActionTaken(any(), any()) } returns true
    }
    private val blockingForced = MutableStateFlow<CheckUpdateResponse?>(null)
    private val updateChecker: StartupUpdateChecker = mockk(relaxed = true) {
        every { state } returns updateState
        every { blockingForced } returns this@IndexStartupPromptHostTest.blockingForced
    }

    /** As the checker publishes: the forced offer also gates until a hand-off expires. */
    private fun setUpdate(state: UpdateCheckState) {
        updateState.value = state
        blockingForced.value = (state as? UpdateCheckState.Forced)?.response
    }
    private val securityChecks: StartupSecurityChecks = mockk(relaxed = true) { every { pending } returns securityPending }
    private val updateManager: UpdateManager = mockk(relaxed = true)
    private val dialog: ComposeDialog = mockk(relaxed = true)
    private val onClosed = slot<((DialogCloseReason) -> Unit)?>()

    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var host: IndexStartupPromptHost

    private val notificationsDialog = PromptTicket(PromptId.NOTIFICATIONS, seq = 1, issue = ReadinessIssue.APP_NOTIFICATIONS_OFF)

    @Before
    fun setUp() {
        mockkObject(ComposeDialogManager, PackageUtil)
        every { PackageUtil.getAppName() } returns "App"
        every {
            ComposeDialogManager.showMessageDialog(
                context = any(), title = any(), message = any(), confirmText = any(), cancelText = any(),
                showCancel = any(), cancelable = any(), autoDismiss = any(), confirmButtonColor = any(),
                cancelButtonColor = any(), onConfirm = any(), onCancel = any(), onDismiss = any(),
                onClosed = captureNullable(onClosed), content = any(),
            )
        } returns dialog
        controller = Robolectric.buildActivity(FragmentActivity::class.java).create()
        host = IndexStartupPromptHost(coordinator, updateChecker, securityChecks, mockk<UserManager>(relaxed = true))
        host.attach(controller.get(), updateManager, lockPending) { false }
        controller.start()
    }

    @After
    fun tearDown() {
        InactiveDeviceNotifier.clear()
        unmockkAll()
        clearAllMocks()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun verifyDialogs(count: Int) = verify(exactly = count) {
        ComposeDialogManager.showMessageDialog(
            context = any(), title = any(), message = any(), confirmText = any(), cancelText = any(),
            showCancel = any(), cancelable = any(), autoDismiss = any(), confirmButtonColor = any(),
            cancelButtonColor = any(), onConfirm = any(), onCancel = any(), onDismiss = any(),
            onClosed = any(), content = any(),
        )
    }

    @Test
    fun `nothing mounts before resume, then only after the settle delay`() {
        current.value = notificationsDialog
        idle(1_000)
        verifyDialogs(0)

        controller.resume()
        idle(500)
        verifyDialogs(0)
        idle(200)
        verifyDialogs(1)
        verify { coordinator.setHostState(true, false, false) }
        coVerify { coordinator.onShown(notificationsDialog) }
    }

    @Test
    fun `the notifications dialog ignores outside taps but always offers Not now`() {
        current.value = notificationsDialog
        controller.resume()
        idle(700)
        verify {
            ComposeDialogManager.showMessageDialog(
                context = any(), title = any(), message = any(), confirmText = any(), cancelText = any(),
                showCancel = true, cancelable = false, autoDismiss = any(), confirmButtonColor = any(),
                cancelButtonColor = any(), onConfirm = any(), onCancel = any(), onDismiss = any(),
                onClosed = any(), content = any(),
            )
        }

        onClosed.captured!!.invoke(DialogCloseReason.CANCEL)
        idle(100)
        coVerify { coordinator.onDeclined(notificationsDialog) }
    }

    @Test
    fun `nothing mounts while an app-lock decision is pending`() {
        lockPending.value = true
        current.value = notificationsDialog
        controller.resume()
        idle(2_000)
        verifyDialogs(0)

        lockPending.value = false
        idle(700)
        verifyDialogs(1)
    }

    @Test
    fun `a pause cancels the settle delay`() {
        current.value = notificationsDialog
        controller.resume()
        idle(300)
        controller.pause()
        idle(2_000)
        verifyDialogs(0)
        verify(exactly = 0) { coordinator.setHostState(true, any(), any()) }
    }

    @Test
    fun `cancel is a decline, a host dismissal records nothing`() {
        current.value = notificationsDialog
        controller.resume()
        idle(700)
        onClosed.captured!!(DialogCloseReason.CANCEL)
        idle(10)
        coVerify(exactly = 1) { coordinator.onDeclined(notificationsDialog) }

        val fullScreen = PromptTicket(PromptId.FULL_SCREEN, seq = 2, issue = ReadinessIssue.FULL_SCREEN_DENIED)
        current.value = fullScreen
        idle(10)
        // Coordinator preempts: the host removes the dialog itself.
        current.value = null
        idle(10)
        verify(exactly = 1) { dialog.dismiss() }
        onClosed.captured!!(DialogCloseReason.PROGRAMMATIC)
        idle(10)
        coVerify(exactly = 1) { coordinator.onDeclined(any()) }
        coVerify(exactly = 0) { coordinator.onActionTaken(any(), any()) }
    }

    @Test
    fun `confirm persists the action before acting`() {
        current.value = PromptTicket(PromptId.BACKGROUND_CONNECTION, seq = 3)
        controller.resume()
        idle(700)
        onClosed.captured!!(DialogCloseReason.CONFIRM)
        idle(10)
        coVerify(exactly = 1) { coordinator.onActionTaken(current.value!!, false) }
    }

    @Test
    fun `a host destroyed between recording the action and acting aborts it and starts nothing`() {
        val ticket = PromptTicket(PromptId.BACKGROUND_CONNECTION, seq = 3)
        coEvery { coordinator.onActionTaken(ticket, any()) } coAnswers {
            // Recreation lands while the outcome is being written.
            controller.pause().stop().destroy()
            true
        }
        current.value = ticket
        controller.resume()
        idle(700)
        onClosed.captured!!(DialogCloseReason.CONFIRM)
        idle(10)
        coVerify(exactly = 1) { coordinator.onActionAborted(ticket) }
        assertNull(shadowOf(controller.get()).nextStartedActivity)
    }

    @Test
    fun `the abort runs only after a slow action write has finished`() {
        val ticket = PromptTicket(PromptId.BACKGROUND_CONNECTION, seq = 4)
        var written = false
        var abortSawWrite: Boolean? = null
        coEvery { coordinator.onActionTaken(ticket, any()) } coAnswers {
            // The host dies while the write is still in flight.
            controller.pause().stop().destroy()
            delay(50)
            written = true
            true
        }
        coEvery { coordinator.onActionAborted(ticket) } coAnswers { abortSawWrite = written }
        current.value = ticket
        controller.resume()
        idle(700)
        onClosed.captured!!(DialogCloseReason.CONFIRM)
        idle(100)
        assertEquals(true, abortSawWrite)
        assertNull(shadowOf(controller.get()).nextStartedActivity)
    }

    @Test
    fun `after the hand-off gate expired a recheck with the same offer shows the dialog again`() {
        val response = CheckUpdateResponse(update = true, force = true, url = "u", notes = "n", updateWithApk = true, apkHash = "h")
        setUpdate(UpdateCheckState.Forced(response))
        controller.resume()
        idle(700)
        verify(exactly = 1) { updateManager.showForceUpdateDialog(any(), response) }

        blockingForced.value = null
        idle(10)
        blockingForced.value = response.copy()
        idle(10)
        verify(exactly = 2) { updateManager.showForceUpdateDialog(any(), response) }
    }

    @Test
    fun `system request records the trip, then an auto-deny shows the app dialog in the same slot without a pause`() {
        val system = PromptTicket(PromptId.NOTIFICATIONS, seq = 4, issue = ReadinessIssue.APP_NOTIFICATIONS_OFF, viaSystemRequest = true)
        current.value = system
        controller.resume()
        idle(700)
        coVerifyOrder {
            coordinator.onShown(system)
            coordinator.onActionTaken(system)
        }
        verifyDialogs(0)

        // The coordinator answers a silent system denial with the app dialog ticket.
        current.value = PromptTicket(PromptId.NOTIFICATIONS, seq = 5, issue = ReadinessIssue.APP_NOTIFICATIONS_OFF)
        idle(10)
        verifyDialogs(1)
    }

    @Test
    fun `forced update hides the prompt and the security warnings`() {
        current.value = notificationsDialog
        securityPending.value = listOf(SecurityWarning.ROOT)
        val response = CheckUpdateResponse(update = true, force = true, url = "u", notes = "n", updateWithApk = false, apkHash = "h")
        setUpdate(UpdateCheckState.Forced(response))
        controller.resume()
        idle(700)
        verify(exactly = 1) { updateManager.showForceUpdateDialog(any(), response) }
        verifyDialogs(0)

        // Recomposition of the same state does not stack a second forced dialog.
        current.value = null
        idle(10)
        verify(exactly = 1) { updateManager.showForceUpdateDialog(any(), response) }
    }

    @Test
    fun `security warnings show one at a time and Ignore moves to the next`() {
        securityPending.value = listOf(SecurityWarning.SIGNATURE, SecurityWarning.ROOT)
        controller.resume()
        idle(700)
        verifyDialogs(1)
        onClosed.captured!!(DialogCloseReason.CANCEL)
        verify { securityChecks.ignore(SecurityWarning.SIGNATURE) }
        securityPending.value = listOf(SecurityWarning.ROOT)
        idle(10)
        verifyDialogs(2)
    }

    @Test
    fun `a prompt the coordinator removes is unmounted while the activity is stopped`() {
        current.value = notificationsDialog
        controller.resume()
        idle(700)
        verifyDialogs(1)

        controller.pause().stop()
        // For example a gate preempts it while the app is in the background.
        current.value = null
        idle(10)
        verify(exactly = 1) { dialog.dismiss() }
        onClosed.captured!!(DialogCloseReason.PROGRAMMATIC)
        idle(10)
        coVerify(exactly = 0) { coordinator.onDeclined(any()) }
    }

    @Test
    fun `an unanswered prompt stays mounted across the background and a recreated host renders it again`() {
        current.value = notificationsDialog
        controller.resume()
        idle(700)
        verifyDialogs(1)

        controller.pause().stop()
        idle(10)
        controller.start().resume()
        idle(700)
        verify(exactly = 0) { dialog.dismiss() }
        verifyDialogs(1)

        // Recreated in the background: the new instance renders the same carried ticket.
        val recreated = Robolectric.buildActivity(FragmentActivity::class.java).create()
        IndexStartupPromptHost(coordinator, updateChecker, securityChecks, mockk<UserManager>(relaxed = true))
            .attach(recreated.get(), updateManager, lockPending) { false }
        recreated.start().resume()
        idle(700)
        verifyDialogs(2)
        coVerify(atLeast = 2) { coordinator.onShown(notificationsDialog) }
    }

    @Test
    fun `an app-wide gate unmounts the prompt and blocks new ones until it clears`() {
        current.value = notificationsDialog
        controller.resume()
        idle(700)
        verifyDialogs(1)

        InactiveDeviceNotifier.raise()
        idle(10)
        verify(exactly = 1) { dialog.dismiss() }
        // The coordinator answers the gate by re-issuing after it clears; nothing mounts meanwhile.
        current.value = PromptTicket(PromptId.NOTIFICATIONS, seq = 7, issue = ReadinessIssue.APP_NOTIFICATIONS_OFF)
        idle(10)
        verifyDialogs(1)

        InactiveDeviceNotifier.clear()
        idle(10)
        verifyDialogs(2)
    }

    @Test
    fun `an expired hand-off gate lets the prompt show`() {
        val response = CheckUpdateResponse(update = true, force = true, url = "u", notes = "n", updateWithApk = true, apkHash = "h")
        setUpdate(UpdateCheckState.Forced(response))
        current.value = notificationsDialog
        controller.resume()
        idle(700)
        verifyDialogs(0)

        // Hand-off expiry: the result stays Forced, only the gate is released.
        blockingForced.value = null
        idle(10)
        verify(exactly = 1) { updateManager.dismissForceUpdateDialog() }
        verifyDialogs(1)
    }

    @Test
    fun `forced dialog is replaced by a new offer and taken down when the offer is withdrawn`() {
        val h1 = CheckUpdateResponse(update = true, force = true, url = "u1", notes = "n", updateWithApk = false, apkHash = "h1")
        val h2 = h1.copy(url = "u2", apkHash = "h2")
        setUpdate(UpdateCheckState.Forced(h1))
        controller.resume()
        idle(700)
        verify(exactly = 1) { updateManager.showForceUpdateDialog(any(), h1) }

        setUpdate(UpdateCheckState.Forced(h1.copy()))
        idle(10)
        verify(exactly = 1) { updateManager.showForceUpdateDialog(any(), h1) }

        setUpdate(UpdateCheckState.Forced(h2))
        idle(10)
        verify(exactly = 1) { updateManager.showForceUpdateDialog(any(), h2) }

        setUpdate(UpdateCheckState.NoUpdate)
        idle(10)
        verify(exactly = 1) { updateManager.dismissForceUpdateDialog() }
    }
}
