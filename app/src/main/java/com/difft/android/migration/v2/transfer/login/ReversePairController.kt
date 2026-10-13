package com.difft.android.migration.v2.transfer.login

import android.content.Context
import android.util.Base64
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.migration.v2.container.FormatCompat
import com.difft.android.migration.v2.transfer.DeviceNames
import com.difft.android.migration.v2.transfer.MigrationEnvironment
import com.difft.android.migration.v2.transfer.MigrationTransferStateHolder
import com.difft.android.migration.v2.transfer.TransferState
import com.difft.android.p2p.android.client.EndpointRace
import com.difft.android.p2p.android.client.PrepareUploadReasons
import com.difft.android.p2p.android.client.ReversePairClient
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.model.ReversePairDevice
import com.difft.android.p2p.engine.model.ReversePairListen
import com.difft.android.p2p.engine.model.ReversePairPrecheck
import com.difft.android.p2p.engine.model.ReversePairRequest
import com.difft.android.p2p.engine.model.ReversePairResponse
import com.difft.android.p2p.engine.session.PairingSessionRegistry
import com.difft.android.p2p.engine.upload.MigrationRejectReason
import com.difft.android.p2p.engine.session.PairingSuperseded
import java.net.SocketException
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The receiver role's reverse pairing: this device scanned a computer's export code, so instead of
 * waiting to be scanned it asks that computer to connect back to the listener already up here and
 * upload. The bytes still travel the ordinary upload path; only the introduction is reversed.
 *
 * Order matters and is the whole design: the pending pairing session is registered **before** the
 * request goes out, so a `/prepare-upload` that beats the peer's own 200 is already authorized.
 * Nothing in here is persisted — a process death during pairing is a re-scan, as with any listener.
 *
 * The pair token and the scanned transfer key are live credentials and are never logged.
 */
internal class ReversePairController(
    private val appContext: Context,
    private val registry: PairingSessionRegistry,
    private val environmentHelper: EnvironmentHelper,
    private val appVersion: String,
    private val account: String,
    /** Address race seam; the production value is the same one the sender role uses. */
    private val resolve: suspend (QrPayload) -> QrPayload? = { EndpointRace.resolve(it) },
    /** `/reverse-pair` seam, so the state machine can be driven without a socket. */
    private val pair: suspend (QrPayload, ReversePairRequest) -> ReversePairResponse =
        { target, body -> ReversePairClient(target).reversePair(body, target.pin) },
) {

    /** Completed only by a 200; a failure resolves it exceptionally only while the phase is PENDING. */
    private val response = CompletableDeferred<ReversePairResponse>()

    /**
     * Registers the pairing session, asks [mac] to connect back to [listen], and returns once the
     * outcome belongs to someone else: the upload path (the computer connected) or a terminal state
     * written here (it never did, or the request was refused).
     *
     * [scope] must be the service scope, not this call's: a `/reverse-pair` response that fails
     * minutes late — after an upload was accepted and is streaming — must neither cancel the receiver
     * role nor keep this call from returning, and a `SupervisorJob` child with its own `runCatching`
     * is what guarantees both.
     */
    suspend fun run(scope: CoroutineScope, mac: QrPayload, listen: QrPayload) {
        val resolved = resolve(mac)
            ?: throw SocketException("none of the ${mac.candidates.size} advertised addresses answered")
        val handle = registry.register(
            pin = mac.pin,
            transferKey = mac.transferKey,
            pairToken = randomPairToken(),
        )
        L.i { "[MigrationV2][P2P][Rx] pairing session registered gen=${handle.generation} (token not logged)" }
        val peerAlias = mac.alias.takeIf { it.isNotBlank() }

        // No dispatcher of its own: [scope] is the service scope, which is already IO-bound, and an
        // explicit one here would only put the call outside a test's virtual time.
        scope.launch {
            runCatching { pair(resolved, body(handle, listen)) }
                .onSuccess { response.complete(it) }
                .onFailure { e -> onPairCallFailed(handle.generation, e) }
        }

        MigrationTransferStateHolder.update(TransferState.AwaitingDesktopConfirm(peerAlias))
        try {
            coroutineScope {
                // runCatching inside the async: a PairingSuperseded thrown by a child would otherwise
                // cancel this scope before the select could resolve. The result still has to be
                // inspected — an evicted session completes this wait too, and is not a connect.
                val connected = async {
                    runCatching { registry.awaitPhaseAtLeast(handle.generation, PairingSessionRegistry.Phase.CONNECTED) }.isSuccess
                }
                val connectWithinMs: Long? = select {
                    response.onAwait { it.connectWithinMs.clampedConnectWindow() }
                    // The prepare-upload beat the 200: there is nothing left to arm a deadline for.
                    connected.onAwait { reached -> if (reached) null else throw PairingSuperseded() }
                }
                // Release the loser explicitly: coroutineScope joins its children, and the losing one
                // is parked in awaitPhaseAtLeast, so without this run() would never return.
                connected.cancel()
                if (connectWithinMs != null) awaitConnectOrFail(handle, peerAlias, connectWithinMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PairingSuperseded) {
            // A newer scan registered its own session and owns the screen from here. With no newer
            // session the live one was evicted (the PIN cap): that must fail visibly, or the page
            // would wait for a connect that can never come.
            if (registry.isActive()) {
                L.i { "[MigrationV2][P2P][Rx] pairing superseded by a newer scan — this run ends quietly" }
            } else {
                L.w { "[MigrationV2][P2P][Rx] pairing session evicted while waiting for the computer" }
                MigrationTransferStateHolder.update(TransferState.Failed(MigrationRejectReason.PAIR_TOKEN.wire))
            }
        } catch (e: Throwable) {
            val reason = ReversePairReasons.of(e)
            L.w { "[MigrationV2][P2P][Rx] reverse-pair failed before any connect reason=$reason" }
            MigrationTransferStateHolder.update(TransferState.Failed(reason))
        } finally {
            if (registry.phase(handle.generation) == PairingSessionRegistry.Phase.PENDING) {
                registry.invalidate(handle.generation)
            }
        }
    }

    /**
     * The late-failure gate. Once a `/prepare-upload` for this generation has been accepted, the
     * upload owns the outcome: a pairing response that fails afterwards is noise and must not abort a
     * transfer that is already streaming. Never throws — the caller is a fire-and-forget launch.
     */
    private fun onPairCallFailed(generation: Long, e: Throwable) {
        when (registry.phase(generation)) {
            PairingSessionRegistry.Phase.CONNECTED, PairingSessionRegistry.Phase.UPLOADING ->
                // `response` is deliberately left incomplete: reaching CONNECTED is exactly what makes
                // the select's other clause fire, so no waiter is left parked on it.
                L.i {
                    "[MigrationV2][P2P][Rx] reverse-pair response lost after the upload started — " +
                        "ignored (${e.javaClass.simpleName})"
                }
            PairingSessionRegistry.Phase.PENDING -> response.completeExceptionally(e)
            null -> L.i { "[MigrationV2][P2P][Rx] reverse-pair failure for a superseded generation — ignored" }
        }
    }

    /** After the confirmation: the computer has this long to open `/prepare-upload` on our listener. */
    private suspend fun awaitConnectOrFail(
        handle: PairingSessionRegistry.Handle,
        peerAlias: String?,
        connectWithinMs: Long,
    ) {
        MigrationTransferStateHolder.update(TransferState.AwaitingDesktopConnect(peerAlias, connectWithinMs))
        val reached: Boolean? = withTimeoutOrNull(connectWithinMs) {
            runCatching { registry.awaitPhaseAtLeast(handle.generation, PairingSessionRegistry.Phase.CONNECTED) }.isSuccess
        }
        when (reached) {
            null -> {
                L.w { "[MigrationV2][P2P][Rx] no prepare-upload within ${connectWithinMs}ms of the confirmation" }
                registry.invalidate(handle.generation)
                MigrationTransferStateHolder.update(TransferState.Failed(PrepareUploadReasons.DESKTOP_NOT_CONNECTED))
            }
            // Superseded or evicted while waiting: run()'s handler decides which of the two it was.
            false -> throw PairingSuperseded()
            true -> Unit
        }
    }

    private fun body(handle: PairingSessionRegistry.Handle, listen: QrPayload) = ReversePairRequest(
        device = ReversePairDevice(
            alias = DeviceNames.local(appContext),
            appVersion = appVersion,
            // Our own receiver certificate: what the computer pins when it connects back.
            fingerprint = listen.fp,
        ),
        listen = ReversePairListen(addresses = listen.candidates.take(MAX_ADVERTISED_ADDRESSES), port = listen.port),
        pairToken = handle.pairToken,
        precheck = ReversePairPrecheck(
            env = MigrationEnvironment.wireName(environmentHelper),
            formatVersion = FormatCompat.SUPPORTED_FORMAT_VERSION,
            account = account,
        ),
    )

    /** A hostile or buggy peer must not be able to pin the receive screen for an hour. */
    private fun Long?.clampedConnectWindow(): Long =
        (this ?: DEFAULT_CONNECT_MS).coerceIn(MIN_CONNECT_MS, MAX_CONNECT_MS)

    /** 32 bytes of entropy, base64url. Single-use, never logged. */
    private fun randomPairToken(): String =
        Base64.encodeToString(
            ByteArray(PAIR_TOKEN_BYTES).also { SecureRandom().nextBytes(it) },
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    companion object {
        const val PAIR_TOKEN_BYTES = 32

        /** Addresses advertised to the peer; more than this is noise on any real LAN. */
        const val MAX_ADVERTISED_ADDRESSES = 4

        @VisibleForTesting internal const val MIN_CONNECT_MS = 5_000L

        @VisibleForTesting internal const val MAX_CONNECT_MS = 120_000L

        /** What a peer that names no window is assumed to mean. */
        @VisibleForTesting internal const val DEFAULT_CONNECT_MS = 30_000L
    }
}
