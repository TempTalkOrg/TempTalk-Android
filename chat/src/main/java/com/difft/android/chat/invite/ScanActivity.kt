package com.difft.android.chat.invite

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import cn.bingoogolapple.qrcode.core.QRCodeView
import com.difft.android.base.BaseActivity
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchSinglePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ActivityScanBinding
import com.difft.android.network.UrlManager
import com.hi.dhl.binding.viewbind
import com.difft.android.base.widget.ComposeDialogManager
import com.luck.picture.lib.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.util.ServiceUtil
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
@AndroidEntryPoint
class ScanActivity : BaseActivity(), QRCodeView.Delegate {

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, ScanActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityScanBinding by viewbind()

    @Inject
    lateinit var inviteUtils: InviteUtils

    @Inject
    lateinit var urlManager: UrlManager

    @Inject
    lateinit var linkDeviceUtils: LinkDeviceUtils

    private val onCameraPermissionForScan = registerPermission {
        onCameraPermissionForScanResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // check permission
        // callback to scan in onCameraPermissionForScanResult
        onCameraPermissionForScan.launchSinglePermission(Manifest.permission.CAMERA)

        mBinding.ibBack.setOnClickListener { finish() }
    }

    private fun initZxingView() {
        mBinding.zxingview.setDelegate(this)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                mBinding.zxingview.startCamera() // 打开后置摄像头开始预览，但是并未开始识别
                mBinding.zxingview.startSpotAndShowRect() // 显示扫描框，并开始识别
            } catch (e: Exception) {
                L.e { "[ScanActivity] initView error: ${e.stackTraceToString()}" }
            }
        }
    }

    override fun onStop() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                mBinding.zxingview.stopCamera()
            } catch (e: Exception) {
                L.e { "[ScanActivity] stopCamera error: ${e.stackTraceToString()}" }
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        mBinding.zxingview.onDestroy() // 销毁二维码扫描控件
        super.onDestroy()
    }

    override fun onScanQRCodeSuccess(result: String) {
        ServiceUtil.getVibrator(this).vibrate(50)

        val uri = result.toUri()
        if (uri.scheme?.startsWith("http") == true) {
            //域名白名单检测
            if (urlManager.isTrustedHost(uri.host ?: "")) {
                L.i { "isInviteLinkUrl: ${urlManager.isInviteLinkUrl(result)}" }
                if (urlManager.isInviteLinkUrl(result)) {
                    val code = uri.getQueryParameter("pi") ?: ""
                    inviteUtils.queryByInviteCode(this, code, true)
                } else if (urlManager.isGroupInviteLinkUrl(result)) {
                    val code = uri.getQueryParameter("i") ?: ""
                    inviteUtils.joinGroupByInviteCode(code, this, true)
                } else {
                    openExternalBrowser(result)
                    finish()
                }
            } else {
                openExternalBrowser(result)
                finish()
            }
        } else if (uri.scheme.equals("tsdevice") || uri.scheme.equals("chative")) {
            val resultStr = result.replace("tsdevice:/", "tsdevice://").toUri()
            val ephemeralId = resultStr.getQueryParameter("uuid")
            val publicKeyEncoded = resultStr.getQueryParameter("pub_key")
            linkDeviceUtils.linkDevice(this, ephemeralId, publicKeyEncoded, true)
        } else {
            showResultContent(result)
        }
    }

    private fun showResultContent(result: String?) {
        result?.let { ToastUtil.showLong(it) }
        finish()
    }

    override fun onCameraAmbientBrightnessChanged(isDark: Boolean) {
    }

    override fun onScanQRCodeOpenCameraError() {

    }

    private fun onCameraPermissionForScanResult(permissionState: PermissionUtil.PermissionState) {
        when (permissionState) {
            PermissionUtil.PermissionState.Denied -> {
                L.d { "onCameraPermissionForScanResult: Denied" }
                ToastUtils.showToast(this, getString(R.string.not_granted_necessary_permissions))
                finish()
            }

            PermissionUtil.PermissionState.Granted -> {
                L.d { "onCameraPermissionForScanResult: Granted" }
                initZxingView()
            }

            PermissionUtil.PermissionState.PermanentlyDenied -> {
                L.d { "onCameraPermissionForScanResult: PermanentlyDenied" }
                ComposeDialogManager.showMessageDialog(
                    context = this,
                    title = getString(R.string.tip),
                    message = getString(R.string.no_permission_camera_tip),
                    confirmText = getString(R.string.notification_go_to_settings),
                    cancelText = getString(R.string.notification_ignore),
                    cancelable = false,
                    onConfirm = {
                        PermissionUtil.launchSettings(this)
                    },
                    onCancel = {
                        ToastUtils.showToast(
                            this, getString(R.string.not_granted_necessary_permissions)
                        )
                    },
                    onDismiss = {
                        finish()
                    }
                )
            }
        }
    }
}