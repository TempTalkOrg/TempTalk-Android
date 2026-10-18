package com.difft.android.chat.invite

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
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
import com.difft.android.base.migration.PeerApps

@AndroidEntryPoint
class ScanActivity : BaseActivity() {

    companion object {
        /** When true, the raw scanned string is returned via setResult instead of being handled in-place. */
        const val EXTRA_RETURN_RESULT = "extra_return_result"
        const val EXTRA_SCAN_RESULT = "extra_scan_result"

        /** Optional page title and one or two lines of guidance under the frame (the migration pairing page's scan state). */
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_HINT = "extra_hint"
        const val EXTRA_HINT_SECONDARY = "extra_hint_secondary"

        /**
         * Set by a result caller that sends data (the migration send screen) so a computer's export
         * code is declined here, with the scanner still open. The home scanner always declines it.
         */
        const val EXTRA_DECLINE_COMPUTER_EXPORT_CODE = "extra_decline_computer_export_code"

        /**
         * Set by the migration screens: the page takes the migration design's scan state (black
         * header and ground, white title and back icon, the corner-only viewfinder). The home
         * scanner keeps its own look.
         */
        const val EXTRA_MIGRATION_STYLE = "extra_migration_style"

        fun startActivity(activity: Activity) {
            val intent = Intent(activity, ScanActivity::class.java)
            activity.startActivity(intent)
        }

        /** Builds an intent that makes ScanActivity return the scanned string to the caller. */
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

    /** The caution shown under the guidance after this scanner declined a code. */
    private var declinedNotice by mutableStateOf<String?>(null)
    private var orientationListener: OrientationEventListener? = null

    private val onCameraPermissionForScan = registerPermission {
        onCameraPermissionForScanResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // check permission
        // callback to scan in onCameraPermissionForScanResult
        onCameraPermissionForScan.launchSinglePermission(Manifest.permission.CAMERA)

        mBinding.ibBack.setOnClickListener { finish() }
        applyCallerGuidance()
    }

    /**
     * A caller may dress the scanner for its own step; without extras the page is the plain home
     * scanner. The bottom block is always on the dark camera ground, so it is drawn in dark colours.
     */
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

    /** Black page and header with light content, and the migration viewfinder. */
    private fun applyMigrationStyle() {
        val black = ContextCompat.getColor(this, BaseR.color.black)
        val white = ContextCompat.getColor(this, BaseR.color.white)
        mBinding.root.setBackgroundColor(black)
        mBinding.header.setBackgroundColor(black)
        mBinding.tvTitle.setTextColor(white)
        mBinding.ibBack.imageTintList = ColorStateList.valueOf(white)
        mBinding.scanOverlay.migrationStyle = true
        // Dark bar styles, not just light icons: below API 29 the default edge-to-edge paints a
        // near-white navigation scrim in light mode, which white buttons would vanish into.
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
            onResult = { result -> onScanQRCodeSuccess(result) }, // already on the main thread
            onError = { onScanQRCodeOpenCameraError() }, // already on the main thread
        ).also {
            cameraController = it
            it.start()
        }
        // Camera is starting on the permission-granted path — now (and only now) run the scan line.
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
        // Resume scan-active sensors/visuals only if scanning has begun (camera bound on the
        // permission-granted path → orientationListener is non-null). Symmetric with onStop; avoids
        // running them on the pre-permission / denied paths.
        orientationListener?.let { listener ->
            if (listener.canDetectOrientation()) listener.enable()
            mBinding.scanOverlay.startScanLine()
        }
        // Re-arm the one-shot scan latch on every foreground edge: if a previous decode took an async
        // branch that did NOT finish this Activity, the analyzer would otherwise stay latched and dead.
        // This is a lifecycle edge (not per-frame), so a result currently being handled is not looped.
        cameraController?.rearm()
    }

    override fun onStop() {
        // Stop sensor callbacks while backgrounded: CameraX use cases are paused, so firing
        // updateTargetRotation would burn CPU/battery on an idle camera. Re-enabled in onResume.
        orientationListener?.disable()
        mBinding.scanOverlay.stopScanLine() // no scan line while backgrounded — saves CPU
        super.onStop()
    }

    override fun onDestroy() {
        orientationListener?.disable()
        mBinding.scanOverlay.stopScanLine()
        cameraController?.shutdown()
        super.onDestroy()
    }

    /** Camera failed to open/bind: the preview is a dead black surface. Tell the user and bail. */
    private fun onScanQRCodeOpenCameraError() {
        ToastUtils.showToast(this, getString(R.string.scan_camera_open_failed))
        finish()
    }

    private fun onScanQRCodeSuccess(result: String) {
        // A frame decoded just before teardown can post this on the main thread after the Activity is
        // gone — vibrate/route on a dead context. Drop anything that arrives below STARTED.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        // A computer's export code can only be taken by a phone that is signing in. Starting a send
        // to it fails at the computer, so say where it belongs and keep scanning instead.
        if (declinesComputerExportCode && PeerApps.isComputerExportQr(result)) {
            declineComputerExportCode(result)
            return
        }

        ServiceUtil.getVibrator(this).vibrate(50)

        // Caller (e.g. proxy settings) just wants the raw string back.
        if (returnResult) {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SCAN_RESULT, result))
            finish()
            return
        }

        // A migration pairing code from another device's receive screen: hand it to the send screen
        // exactly as its own scanner would, so the general "scan" entry works for migration too.
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
            //域名白名单检测
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
            // The sign-in confirmation page owns the decision about syncing history; it gets the raw
            // migration query values and decides what the code means.
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

/**
 * Maps OrientationEventListener sensor degrees (0..359, clockwise from natural) to the
 * [Surface] ROTATION_* constant CameraX expects. Quadrant-centered thresholds (±45° around each
 * cardinal) match the standard Android camera mapping. ORIENTATION_UNKNOWN is filtered by the caller.
 *
 * File-level `internal` so the unit test (T20) can call it directly without reflection. Returns a
 * [Surface] ROTATION_* constant.
 */
internal fun Int.toSurfaceRotation(): Int = when (this) {
    in 45..134 -> Surface.ROTATION_270
    in 135..224 -> Surface.ROTATION_180
    in 225..314 -> Surface.ROTATION_90
    else -> Surface.ROTATION_0 // 315..359 + 0..44 (natural / portrait-up)
}
