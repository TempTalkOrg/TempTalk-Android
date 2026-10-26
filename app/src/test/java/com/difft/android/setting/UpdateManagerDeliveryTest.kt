package com.difft.android.setting

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import com.difft.android.R
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.UrlManager
import com.difft.android.network.responses.AppVersionResponse
import com.difft.android.setting.data.CheckUpdateResponse
import com.difft.android.setting.repo.SettingRepo
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UpdateManager keeps the check and the dialogs; every confirmed offer goes to the channel's
 * [UpdateDelivery], whatever the server's updateWithApk says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UpdateManagerDeliveryTest {

    private class FakeDelivery(override var isInProgress: Boolean = false) : UpdateDelivery {
        val delivered = mutableListOf<Pair<UpdateOffer, Boolean>>()
        var lastHandOff: (() -> Unit)? = null

        override fun deliver(context: Context, offer: UpdateOffer, isForce: Boolean, onForceHandOff: () -> Unit) {
            delivered += offer to isForce
            lastHandOff = onForceHandOff
        }

        override fun attach(activity: FragmentActivity, onForceHandOff: () -> Unit) = Unit
    }

    private val settingRepo: SettingRepo = mockk()
    private val environmentHelper: EnvironmentHelper = mockk()
    private val httpClient: ChativeHttpClient = mockk()
    private val urlManager: UrlManager = mockk(relaxed = true)
    private val userManager: UserManager = mockk(relaxed = true)
    private val delivery = FakeDelivery()

    private val onConfirm = slot<() -> Unit>()
    private val showCancel = slot<Boolean>()
    private val cancelable = slot<Boolean>()
    private val autoDismiss = slot<Boolean>()
    private val dialog: ComposeDialog = mockk(relaxed = true)

    private lateinit var activity: FragmentActivity
    private lateinit var manager: UpdateManager

    @Before
    fun setup() {
        mockkObject(ComposeDialogManager, ToastUtil, PackageUtil)
        every { ToastUtil.show(any<Int>()) } just runs
        every { ToastUtil.show(any<String>()) } just runs
        every { PackageUtil.getAppVersionName() } returns "1.0.0"
        every { PackageUtil.getAppVersionCode() } returns 100
        every {
            ComposeDialogManager.showMessageDialog(
                context = any(), title = any(), message = any(), confirmText = any(), cancelText = any(),
                showCancel = capture(showCancel), cancelable = capture(cancelable), autoDismiss = capture(autoDismiss),
                confirmButtonColor = any(), cancelButtonColor = any(), onConfirm = capture(onConfirm),
                onCancel = any(), onDismiss = any(), onClosed = any(), content = any()
            )
        } returns dialog
        every { environmentHelper.isInsiderChannel() } returns false

        activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        manager = UpdateManager(settingRepo, environmentHelper, httpClient, urlManager, userManager, delivery)
    }

    @After
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    @Test
    fun confirm_delegatesOfferToDelivery_forEitherUpdateWithApk() {
        listOf(false, true).forEach { withApk ->
            delivery.delivered.clear()
            onConfirm.clear()
            serverOffers(force = false, updateWithApk = withApk)

            manager.checkUpdate(activity, isFromSetting = false)
            awaitDialog()
            onConfirm.captured.invoke()

            assertEquals(listOf(UpdateOffer("u", "h") to false), delivery.delivered, "updateWithApk=$withApk")
        }
    }

    @Test
    fun forceDialog_isNonDismissable_andHandOffClosesIt() {
        serverOffers(force = true, updateWithApk = false)

        manager.checkUpdate(activity, isFromSetting = false)
        awaitDialog()

        assertEquals(false, showCancel.captured)
        assertEquals(false, cancelable.captured)
        assertEquals(false, autoDismiss.captured)
        onConfirm.captured.invoke()
        assertEquals(listOf(UpdateOffer("u", "h") to true), delivery.delivered)

        delivery.lastHandOff!!.invoke()
        verify(exactly = 1) { dialog.dismiss() }
    }

    @Test
    fun forceDialog_newOfferReplacesTheOld_andHandOffClosesTheCurrentOne() {
        val first: ComposeDialog = mockk(relaxed = true)
        val second: ComposeDialog = mockk(relaxed = true)
        every {
            ComposeDialogManager.showMessageDialog(
                context = any(), title = any(), message = any(), confirmText = any(), cancelText = any(),
                showCancel = any(), cancelable = any(), autoDismiss = any(), confirmButtonColor = any(),
                cancelButtonColor = any(), onConfirm = capture(onConfirm), onCancel = any(), onDismiss = any(),
                onClosed = any(), content = any()
            )
        } returnsMany listOf(first, second)
        val h1 = CheckUpdateResponse(update = true, force = true, url = "u1", notes = "n", updateWithApk = false, apkHash = "h1")

        manager.showForceUpdateDialog(activity, h1)
        manager.showForceUpdateDialog(activity, h1.copy(url = "u2", apkHash = "h2"))
        verify(exactly = 1) { first.dismiss() }
        verify(exactly = 0) { second.dismiss() }

        onConfirm.captured.invoke()
        assertEquals(listOf(UpdateOffer("u2", "h2") to true), delivery.delivered)
        delivery.lastHandOff!!.invoke()
        verify(exactly = 1) { second.dismiss() }
        verify(exactly = 1) { first.dismiss() }
    }

    @Test
    fun forceDialog_withdrawnOfferIsDismissed() {
        manager.showForceUpdateDialog(activity, CheckUpdateResponse(update = true, force = true, url = "u", notes = "n", updateWithApk = false, apkHash = "h"))
        manager.dismissForceUpdateDialog()
        manager.dismissForceUpdateDialog()
        verify(exactly = 1) { dialog.dismiss() }
    }

    @Test
    fun confirm_isRefused_whileDeliveryInProgress() {
        delivery.isInProgress = true
        serverOffers(force = false, updateWithApk = false)

        manager.checkUpdate(activity, isFromSetting = false)
        awaitDialog()
        onConfirm.captured.invoke()

        verify(exactly = 1) { ToastUtil.show(R.string.status_upgrade_downloading) }
        assertTrue(delivery.delivered.isEmpty())
    }

    @Test
    fun insiderFeed_deliversWithoutDialog() {
        every { environmentHelper.isInsiderChannel() } returns true
        coEvery { httpClient.httpService.getAppVersionConfigs(any()) } returns
            AppVersionResponse(versionName = "2.0.0", versionCode = 200, url = "iu", hash = "ih")

        manager.checkUpdate(activity, isFromSetting = false)
        awaitCondition { delivery.delivered.isNotEmpty() }

        assertEquals(listOf(UpdateOffer("iu", "ih") to false), delivery.delivered)
        assertTrue(!onConfirm.isCaptured, "the insider feed installs without a prompt")
    }

    @Test
    fun checkError_fromSettings_toastsAndDeliversNothing() {
        every { ComposeDialogManager.showWait(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { settingRepo.checkUpdate(any(), any()) } throws IOException("x")

        manager.checkUpdate(activity, isFromSetting = true)
        awaitCondition { runCatching { verify { ToastUtil.show("x") } }.isSuccess }

        assertTrue(delivery.delivered.isEmpty())
        verify { userManager.update(any(), any()) }
    }

    private fun serverOffers(force: Boolean, updateWithApk: Boolean) {
        val response = CheckUpdateResponse(
            update = true, force = force, url = "u", notes = "n", updateWithApk = updateWithApk, apkHash = "h"
        )
        coEvery { settingRepo.checkUpdate(any(), any()) } returns
            BaseResponse(ver = 1, status = 0, reason = null, data = response)
    }

    private fun awaitDialog() = awaitCondition { onConfirm.isCaptured }

    /** checkUpdate hops to Dispatchers.IO and back to the main looper; poll with a bound. */
    private fun awaitCondition(condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        error("condition not met within 2s")
    }
}
