package com.difft.android.chat.invite

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.difft.android.base.R as BaseR
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.BaseActivity
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.android.permission.PermissionUtil.launchSinglePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.security.SafeLinkOpener
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ActivityScanBinding
import com.difft.android.chat.util.ServiceUtil
import com.difft.android.network.UrlManager
import com.hi.dhl.binding.viewbind
import com.difft.android.selector.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.difft.android.base.migration.LinkMigrationInfo
import com.difft.android.base.ui.sidechrome.sideChromeBar
import com.difft.android.base.ui.theme.tokens.ColorTokens
import com.difft.android.base.widget.floatAsTopBarButton
import androidx.compose.ui.graphics.toArgb
import com.difft.android.base.migration.PeerApps

@AndroidEntryPoint
class ScanActivity : BaseActivity() {

    companion object {
        
        const val EXTRA_RETURN_RESULT = "extra_return_result"
        const val EXTRA_SCAN_RESULT = "extra_scan_result"

        
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_HINT = "extra_hint"
        const val EXTRA_HINT_SECONDARY = "extra_hint_secondary"

        



        const val EXTRA_DECLINE_COMPUTER_EXPORT_CODE = "extra_decline_computer_export_code"

        




        const val EXTRA_MIGRATION_STYLE = "extra_migration_style"

        fun startActivity(activity: Activity) {
            val intent = Intent(activity, ScanActivity::class.java)
            activity.startActivity(intent)
        }

        
        fun createResultIntent(context: android.content.Context): Intent =
            Intent(context, ScanActivity::class.java).putExtra(EXTRA_RETURN_RESULT, true)
    }

    private val returnResult: Boolean by lazy { intent.getBooleanExtra(EXTRA_RETURN_RESULT, false) }

    private val declinesComputerExportCode: Boolean by lazy {
        !returnResult || intent.getBooleanExtra(EXTRA_DECLINE_COMPUTER_EXPORT_CODE, false)
    }

    private val mBinding: ActivityScanBinding by viewbind()

    @Inject
    lateinit var inviteUtils: InviteUtils

    @Inject
    lateinit var urlManager: UrlManager


    private var cameraController: ScanCameraController? = null

    
    private var declinedNotice by mutableStateOf<String?>(null)
    private var orientationListener: OrientationEventListener? = null

    private val onCameraPermissionForScan = registerPermission {
        onCameraPermissionForScanResult(it)
    }

    
    override fun participatesInSideChrome(): Boolean = !intent.getBooleanExtra(EXTRA_MIGRATION_STYLE, false)

    override fun padsRootForSideChrome(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        
        onCameraPermissionForScan.launchSinglePermission(Manifest.permission.CAMERA)

        if (intent.getBooleanExtra(EXTRA_MIGRATION_STYLE, false)) {
            
            mBinding.ibBack.setOnClickListener { finish() }
            mBinding.ibBack.floatAsTopBarButton(leading = true, fillColor = ColorTokens.Dark.BackgroundTertiary.toArgb())
        } else {
            sideChromeBar().setBack(mBinding.ibBack, { finish() })
        }
        applyCallerGuidance()
    }

    



    private fun applyCallerGuidance() {
        intent.getStringExtra(EXTRA_TITLE)?.let { mBinding.tvTitle.text = it }
        if (intent.getBooleanExtra(EXTRA_MIGRATION_STYLE, false)) applyMigrationStyle()
        val note = scanNote(intent.getStringExtra(EXTRA_HINT), intent.getStringExtra(EXTRA_HINT_SECONDARY))
        mBinding.composeGuidance.setContent {
            DifftTheme(darkTheme = true, applyWindowBackground = false) {
                ScanGuidance(note = note, notice = declinedNotice)
            }
        }
    }

    
    private fun applyMigrationStyle() {
        val black = ContextCompat.getColor(this, BaseR.color.black)
        val white = ContextCompat.getColor(this, BaseR.color.white)
        mBinding.root.setBackgroundColor(black)
        
        window.setBackgroundDrawable(ColorDrawable(black))
        mBinding.header.setBackgroundColor(black)
        mBinding.tvTitle.setTextColor(white)
        mBinding.ibBack.imageTintList = ColorStateList.valueOf(white)
        mBinding.scanOverlay.migrationStyle = true
        
        
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
    }

    private fun startScan() {
        val controller = ScanCameraController(
            context = this,
            lifecycleOwner = this,
            previewView = mBinding.previewView,
            onResult = { result -> onScanQRCodeSuccess(result) }, 
            onError = { onScanQRCodeOpenCameraError() }, 
        ).also {
            cameraController = it
            it.start()
        }
        
        mBinding.scanOverlay.startScanLine()
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                controller.updateTargetRotation(orientation.toSurfaceRotation())
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    override fun onResume() {
        super.onResume()
        
        
        
        orientationListener?.let { listener ->
            if (listener.canDetectOrientation()) listener.enable()
            mBinding.scanOverlay.startScanLine()
        }
        
        
        
        cameraController?.rearm()
    }

    override fun onStop() {
        
        
        orientationListener?.disable()
        mBinding.scanOverlay.stopScanLine() 
        super.onStop()
    }

    override fun onDestroy() {
        orientationListener?.disable()
        mBinding.scanOverlay.stopScanLine()
        cameraController?.shutdown()
        super.onDestroy()
    }

    
    private fun onScanQRCodeOpenCameraError() {
        ToastUtils.showToast(this, getString(R.string.scan_camera_open_failed))
        finish()
    }

    private fun onScanQRCodeSuccess(result: String) {
        
        
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        
        
        if (declinesComputerExportCode && PeerApps.isComputerExportQr(result)) {
            declineComputerExportCode(result)
            return
        }

        ServiceUtil.getVibrator(this).vibrate(50)

        
        if (returnResult) {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SCAN_RESULT, result))
            finish()
            return
        }

        
        
        if (PeerApps.looksLikeMigrationQr(result)) {
            startActivity(
                Intent()
                    .setClassName(packageName, PeerApps.SEND_ACTIVITY)
                    .putExtra(PeerApps.EXTRA_QR_PAYLOAD, result),
            )
            finish()
            return
        }

        val uri = result.toUri()
        if (uri.scheme?.startsWith("http") == true) {
            
            if (urlManager.isTrustedHost(uri.host ?: "")) {
                if (urlManager.isInviteLinkUrl(result)) {
                    val code = uri.getQueryParameter("pi") ?: ""
                    inviteUtils.queryByInviteCode(this, code, true)
                } else {
                    SafeLinkOpener.open(this, result, onOpen = { finish() })
                }
            } else {
                SafeLinkOpener.open(this, result, onOpen = { finish() })
            }
        } else if (uri.scheme.equals("tsdevice") || uri.scheme.equals("chative")) {
            val resultStr = result.replace("tsdevice:/", "tsdevice://").toUri()
            val ephemeralId = resultStr.getQueryParameter("uuid")
            val publicKeyEncoded = resultStr.getQueryParameter("pub_key")
            if (ephemeralId.isNullOrEmpty() || publicKeyEncoded.isNullOrEmpty()) {
                showResultContent(result)
                return
            }
            
            
            startActivity(
                Intent()
                    .setClassName(packageName, PeerApps.LINK_LOGIN_ACTIVITY)
                    .putExtra(PeerApps.EXTRA_LINK_EPHEMERAL_ID, ephemeralId)
                    .putExtra(PeerApps.EXTRA_LINK_PUBLIC_KEY, publicKeyEncoded)
                    .putExtra(PeerApps.EXTRA_QR_PAYLOAD, resultStr.getQueryParameter(LinkMigrationInfo.QUERY_KEY))
                    .putExtra(PeerApps.EXTRA_LINK_MIGRATION_STATE, resultStr.getQueryParameter(LinkMigrationInfo.STATE_QUERY_KEY)),
            )
            finish()
        } else {
            showResultContent(result)
        }
    }

    private fun declineComputerExportCode(result: String) {
        L.i { "[Scan] declined kind=computer_export" }
        declinedNotice = getString(R.string.scan_computer_export_code_hint)
        cameraController?.skip(result)
    }

    private fun showResultContent(result: String?) {
        result?.let { ToastUtil.showLong(it) }
        finish()
    }

    private fun onCameraPermissionForScanResult(permissionState: PermissionUtil.PermissionState) {
        when (permissionState) {
            PermissionUtil.PermissionState.Denied -> {
                ToastUtils.showToast(this, getString(R.string.not_granted_necessary_permissions))
                finish()
            }

            PermissionUtil.PermissionState.Granted -> {
                startScan()
            }

            PermissionUtil.PermissionState.PermanentlyDenied -> {
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



internal fun Int.toSurfaceRotation(): Int = when (this) {
    in 45..134 -> Surface.ROTATION_270
    in 135..224 -> Surface.ROTATION_180
    in 225..314 -> Surface.ROTATION_90
    else -> Surface.ROTATION_0 
}
