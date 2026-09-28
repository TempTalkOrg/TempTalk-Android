package com.difft.android.migration.v2.transfer.link

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.LinkMigrationInfo
import com.difft.android.migration.v2.export.ExportEstimate
import com.difft.android.p2p.android.client.EndpointRace
import com.difft.android.p2p.android.client.PrepareUploadReasons
import com.difft.android.p2p.android.client.PrepareUploadRejectedException
import com.difft.android.p2p.android.qr.QrPayloadCodec
import com.difft.android.p2p.engine.model.LinkPeerState
import com.difft.android.p2p.engine.model.QrPayload
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** The network and account seams the page needs; the Activity wires the real ones, tests fake them. */
interface LinkLoginGateway {
    suspend fun estimate(): ExportEstimate

    /**
     * Settles which of the Desktop's advertised addresses to talk to (TCP + TLS + pin, first to
     * answer wins); null when none did. The page then keeps the code's first address and lets the
     * precheck report the Desktop unreachable the way it always has.
     */
    suspend fun resolve(target: QrPayload): QrPayload? = EndpointRace.resolve(target)

    /** `link-precheck`; throws [PrepareUploadRejectedException] on a typed refusal, [IOException] when unreachable. */
    suspend fun precheck(target: QrPayload): LinkPeerState

    /**
     * `link-intent`; returns the Desktop's fresh verdict on the account relation (null from an older
     * Desktop). Throws [PrepareUploadRejectedException] on a typed refusal, [IOException] when unreachable.
     */
    suspend fun linkIntent(target: QrPayload, sync: Boolean): LinkPeerState?

    suspend fun link(ephemeralId: String, publicKey: String): Result<Unit>
}

/**
 * Drives the Desktop-login confirmation page. Order on sign-in for a compound code: `link-intent`
 * first, so the Desktop knows the decision at provisioning time, then the link, then — with sync on —
 * hand the payload to the send screen. Any failure of the intent leg degrades to a plain sign-in: the
 * link itself is never blocked by the sync machinery.
 */
class LinkLoginViewModel(
    private val appContext: Context,
    private val ephemeralId: String,
    private val publicKey: String,
    private val rawPayload: String?,
    stateRaw: String?,
    private val gateway: LinkLoginGateway,
) : ViewModel() {

    private val payload: QrPayload? = rawPayload?.let(QrPayloadCodec::decode)?.takeIf { it.transferKey != null }

    /**
     * [payload] with its address settled by [LinkLoginGateway.resolve] — what every request and the
     * send screen use, so the address that answered is the one the session is recorded against.
     */
    @Volatile
    private var target: QrPayload? = payload

    private val _state = MutableStateFlow(deriveLinkLoginState(payload, LinkMigrationInfo.parseState(stateRaw)))
    val state: StateFlow<LinkLoginState> = _state.asStateFlow()

    private val _effects = Channel<LinkLoginEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        L.i { "[MigrationV2][Link] confirmation page state=${_state.value::class.simpleName} plain=${(_state.value as? LinkLoginState.Plain)?.kind}" }
        if (payload != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val estimate = runCatching { gateway.estimate() }
                    .onFailure { e -> L.w { "[MigrationV2][Link] size estimate failed: ${e.message}" } }
                    .getOrNull() ?: return@launch
                updateCompound { copy(estimate = estimate) }
            }
            viewModelScope.launch(Dispatchers.IO) {
                val settled = runCatching { gateway.resolve(payload) }
                    .onFailure { e -> L.w { "[MigrationV2][Link] address race failed: ${e.message}" } }
                    .getOrNull() ?: payload
                target = settled
                precheck(settled)
            }
        }
    }

    fun onIntent(intent: LinkLoginIntent) {
        when (intent) {
            is LinkLoginIntent.SetSync -> updateCompound { copy(syncEnabled = intent.on) }
            is LinkLoginIntent.SetIncludeAttachments -> updateCompound { copy(includeAttachments = intent.on) }
            LinkLoginIntent.SignIn -> signIn()
            LinkLoginIntent.SignInWithoutSync -> signInPlain()
            LinkLoginIntent.Cancel -> _effects.trySend(LinkLoginEffect.Finish)
        }
    }

    /**
     * `link-precheck` on open: one call answers both whether the Desktop is reachable and how its
     * history relates to this account. Same account switches the sync off for good; a stale PIN means
     * the code on screen has rotated, so the page closes for a re-scan.
     */
    private suspend fun precheck(target: QrPayload) {
        try {
            val state = gateway.precheck(target)
            L.i { "[MigrationV2][Link] precheck state=${state.wire}" }
            updateCompound {
                copy(reachable = true, peerState = state, syncEnabled = syncEnabled && state != LinkPeerState.SAME_ACCOUNT)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PrepareUploadRejectedException) {
            if (e.reason.contains(PrepareUploadReasons.PIN_REQUIRED)) {
                L.w { "[MigrationV2][Link] precheck refused: stale PIN" }
                toast(R.string.link_login_pin_expired)
                _effects.trySend(LinkLoginEffect.Finish)
            } else {
                L.w { "[MigrationV2][Link] precheck refused by gate reason=${e.reason}" }
                updateCompound { copy(reachable = true, gateReject = e.reason) }
            }
        } catch (e: IOException) {
            L.i { "[MigrationV2][Link] desktop unreachable: ${e.message}" }
            updateCompound { copy(reachable = false, syncEnabled = false) }
        } catch (e: Exception) {
            // A Desktop that answers but not in this protocol (a 404 from a build without the
            // endpoint, an unparsable body) cannot sync; the login itself is unaffected. Never a crash.
            L.w { "[MigrationV2][Link] precheck failed, sync off: ${e.message}" }
            updateCompound { copy(reachable = false, syncEnabled = false) }
        }
    }

    private fun signIn() {
        val current = _state.value
        if (current.isBusy()) return
        when (current) {
            is LinkLoginState.Plain -> signInPlain()
            is LinkLoginState.Compound -> {
                if (current.reachable == null) return // still prechecking; the button is disabled meanwhile
                // Same account: the Desktop keeps its own history, no intent is sent, plain link.
                if (current.reachable == false || current.gateReject != null || current.peerState == LinkPeerState.SAME_ACCOUNT) {
                    signInPlain()
                    return
                }
                setBusy(true)
                viewModelScope.launch {
                    val target = this@LinkLoginViewModel.target ?: return@launch signInPlain()
                    val sync = current.syncEnabled
                    val verdict = sendIntent(target, sync) ?: return@launch
                    // The intent's answer is the Desktop's current view and supersedes the precheck's.
                    val effective = verdict.state ?: current.peerState
                    val willSync = sync && verdict.reachable && effective != LinkPeerState.SAME_ACCOUNT
                    link(willSync, current.includeAttachments, awaitPeerConfirm = willSync && effective == LinkPeerState.DIFFERENT_ACCOUNT)
                }
            }
        }
    }

    /** What `link-intent` came back with: whether the Desktop answered at all, and its account verdict. */
    private data class IntentVerdict(val reachable: Boolean, val state: LinkPeerState?)

    /**
     * Delivers the decision. Null means the page already handled the outcome (stale PIN → re-scan,
     * gate reject → offer plain sign-in) and sign-in must not continue.
     */
    private suspend fun sendIntent(target: QrPayload, sync: Boolean): IntentVerdict? = try {
        IntentVerdict(reachable = true, state = gateway.linkIntent(target, sync))
    } catch (e: CancellationException) {
        throw e
    } catch (e: PrepareUploadRejectedException) {
        if (e.reason.contains(PrepareUploadReasons.PIN_REQUIRED)) {
            // The code on the Desktop screen has rotated since the scan; it is still there to re-scan.
            L.w { "[MigrationV2][Link] link-intent refused: stale PIN" }
            toast(R.string.link_login_pin_expired)
            _effects.trySend(LinkLoginEffect.Finish)
            null
        } else {
            L.w { "[MigrationV2][Link] link-intent refused by gate reason=${e.reason}" }
            updateCompound { copy(gateReject = e.reason, busy = false) }
            null
        }
    } catch (e: IOException) {
        // Unreachable after the precheck said otherwise: the login goes ahead, only the sync is dropped.
        L.w { "[MigrationV2][Link] link-intent unreachable: ${e.message}" }
        toast(R.string.link_login_unreachable_note)
        IntentVerdict(reachable = false, state = null)
    } catch (e: Exception) {
        // Same as unreachable: an answer this protocol does not know (404, malformed body) drops
        // the sync, not the login — and never the process.
        L.w { "[MigrationV2][Link] link-intent failed, sync off: ${e.message}" }
        toast(R.string.link_login_unreachable_note)
        IntentVerdict(reachable = false, state = null)
    }

    private fun signInPlain() {
        if (_state.value.isBusy()) return
        setBusy(true)
        viewModelScope.launch { link(sync = false, includeAttachments = true) }
    }

    private suspend fun link(sync: Boolean, includeAttachments: Boolean, awaitPeerConfirm: Boolean = false) {
        val result = gateway.link(ephemeralId, publicKey)
        result.onFailure { e ->
            toast(e.message?.takeIf { it.isNotBlank() } ?: appContext.getString(R.string.link_login_failed))
            setBusy(false)
            return
        }
        L.i { "[MigrationV2][Link] linked sync=$sync includeAttachments=$includeAttachments awaitPeerConfirm=$awaitPeerConfirm" }
        // The send screen gets the settled address, not the code's first one, so its service talks
        // to the Desktop that answered without racing again.
        val raw = target?.let { runCatching { QrPayloadCodec.encodeText(it) }.getOrNull() } ?: rawPayload
        if (sync && raw != null) _effects.trySend(LinkLoginEffect.OpenSend(raw, includeAttachments, awaitPeerConfirm))
        _effects.trySend(LinkLoginEffect.Finish)
    }

    private fun toast(resId: Int) = toast(appContext.getString(resId))

    private fun toast(msg: String) {
        _effects.trySend(LinkLoginEffect.Toast(msg))
    }

    private fun setBusy(busy: Boolean) = _state.update { s ->
        when (s) {
            is LinkLoginState.Compound -> s.copy(busy = busy)
            is LinkLoginState.Plain -> s.copy(busy = busy)
        }
    }

    private fun updateCompound(transform: LinkLoginState.Compound.() -> LinkLoginState.Compound) =
        _state.update { s -> if (s is LinkLoginState.Compound) s.transform() else s }

    private fun LinkLoginState.isBusy(): Boolean = when (this) {
        is LinkLoginState.Compound -> busy
        is LinkLoginState.Plain -> busy
    }
}
