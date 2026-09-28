package com.difft.android.migration.v2.transfer.link

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.NewInstanceFactory
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.BuildConfig
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.PeerApps
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.invite.LinkDeviceUtils
import com.difft.android.migration.v2.container.FormatCompat
import com.difft.android.migration.v2.export.ExportEstimate
import com.difft.android.migration.v2.export.MigrationReadSource
import com.difft.android.migration.v2.export.NeutralExporterImpl
import com.difft.android.migration.v2.transfer.DeviceNames
import com.difft.android.migration.v2.transfer.MigrationEnvironment
import com.difft.android.p2p.android.client.SenderHttpClient
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.LinkPeerState
import com.difft.android.p2p.engine.model.MigrationHandshake
import com.difft.android.p2p.engine.model.QrPayload
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Hosts the Desktop-login confirmation page. Opened by the home scanner for every `tsdevice:` QR
 * with the provisioning id/key and the raw migration query values; owns nothing but wiring — the
 * decision logic is in [LinkLoginViewModel], the link itself in [LinkDeviceUtils], and a sync hands
 * over to the standard send screen, which starts on its own.
 */
@AndroidEntryPoint
class LinkLoginActivity : BaseActivity() {

    @Inject lateinit var linkDeviceUtils: LinkDeviceUtils
    @Inject lateinit var readSource: MigrationReadSource
    @Inject lateinit var userManager: StorageBoundUserManager
    @Inject lateinit var environmentHelper: EnvironmentHelper

    private val viewModel: LinkLoginViewModel by lazy {
        ViewModelProvider(
            this,
            object : NewInstanceFactory() {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    LinkLoginViewModel(
                        appContext = applicationContext,
                        ephemeralId = intent.getStringExtra(PeerApps.EXTRA_LINK_EPHEMERAL_ID).orEmpty(),
                        publicKey = intent.getStringExtra(PeerApps.EXTRA_LINK_PUBLIC_KEY).orEmpty(),
                        rawPayload = com.difft.android.base.migration.LinkMigrationInfo.decode(intent.getStringExtra(PeerApps.EXTRA_QR_PAYLOAD)),
                        stateRaw = intent.getStringExtra(PeerApps.EXTRA_LINK_MIGRATION_STATE),
                        gateway = Gateway(),
                    ) as T
            },
        )[LinkLoginViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra(PeerApps.EXTRA_LINK_EPHEMERAL_ID).isNullOrEmpty() ||
            intent.getStringExtra(PeerApps.EXTRA_LINK_PUBLIC_KEY).isNullOrEmpty()
        ) {
            L.w { "[MigrationV2][Link] opened without provisioning parameters — nothing to link" }
            finish()
            return
        }
        val composeView = ComposeView(this)
        composeView.setContent {
            DifftTheme {
                val state by viewModel.state.collectAsState()
                LinkLoginScreen(state = state, onIntent = viewModel::onIntent)
            }
        }
        setContentView(composeView)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is LinkLoginEffect.Toast -> ToastUtil.show(effect.msg)
                        is LinkLoginEffect.OpenSend -> openSend(effect.rawPayload, effect.includeAttachments, effect.awaitPeerConfirm)
                        LinkLoginEffect.Finish -> finish()
                    }
                }
            }
        }
    }

    /** The standard send screen, told to start at once: the user already decided here. */
    private fun openSend(rawPayload: String, includeAttachments: Boolean, awaitPeerConfirm: Boolean) {
        startActivity(
            Intent()
                .setClassName(packageName, PeerApps.SEND_ACTIVITY)
                .putExtra(PeerApps.EXTRA_QR_PAYLOAD, rawPayload)
                .putExtra(PeerApps.EXTRA_FROM_LINK, true)
                .putExtra(PeerApps.EXTRA_AUTO_START, true)
                .putExtra(PeerApps.EXTRA_INCLUDE_ATTACHMENTS, includeAttachments)
                .putExtra(PeerApps.EXTRA_AWAIT_PEER_CONFIRM, awaitPeerConfirm),
        )
    }

    /** Real implementations of the page's seams: live export estimate, pinned HTTPS to the Desktop, the account link. */
    private inner class Gateway : LinkLoginGateway {
        override suspend fun estimate(): ExportEstimate =
            NeutralExporterImpl(readSource)
                .apply { selfUid = userManager.getUserData()?.account.orEmpty() }
                .estimate()

        override suspend fun precheck(target: QrPayload): LinkPeerState =
            SenderHttpClient(target).linkPrecheck(self(), target.pin, handshake())

        override suspend fun linkIntent(target: QrPayload, sync: Boolean): LinkPeerState? =
            SenderHttpClient(target).linkIntent(self(), target.pin, handshake(), sync)

        private fun self() = DeviceInfo(alias = DeviceNames.local(applicationContext), fingerprint = "tx-${UUID.randomUUID().toString().take(8)}")

        private fun handshake() = MigrationHandshake(
            sourceEnvironment = MigrationEnvironment.wireName(environmentHelper),
            sourceAccount = userManager.getUserData()?.account.orEmpty(),
            sourceAppVersion = BuildConfig.VERSION_NAME,
            sourceFormatVersion = FormatCompat.SUPPORTED_FORMAT_VERSION,
        )

        override suspend fun link(ephemeralId: String, publicKey: String): Result<Unit> =
            linkDeviceUtils.linkDevice(ephemeralId, publicKey)
    }
}
