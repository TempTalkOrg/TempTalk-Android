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












internal class ReversePairController(
    private val appContext: Context,
    private val registry: PairingSessionRegistry,
    private val environmentHelper: EnvironmentHelper,
    private val appVersion: String,
    private val account: String,
    
    private val resolve: suspend (QrPayload) -> QrPayload? = { EndpointRace.resolve(it) },
    
    private val pair: suspend (QrPayload, ReversePairRequest) -> ReversePairResponse =
        { target, body -> ReversePairClient(target).reversePair(body, target.pin) },
) {

    
    private val response = CompletableDeferred<ReversePairResponse>()

    









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

        
        
        scope.launch {
            runCatching { pair(resolved, body(handle, listen)) }
                .onSuccess { response.complete(it) }
                .onFailure { e -> onPairCallFailed(handle.generation, e) }
        }

        MigrationTransferStateHolder.update(TransferState.AwaitingDesktopConfirm(peerAlias))
        try {
            coroutineScope {
                
                
                
                val connected = async {
                    runCatching { registry.awaitPhaseAtLeast(handle.generation, PairingSessionRegistry.Phase.CONNECTED) }.isSuccess
                }
                val connectWithinMs: Long? = select {
                    response.onAwait { it.connectWithinMs.clampedConnectWindow() }
                    
                    connected.onAwait { reached -> if (reached) null else throw PairingSuperseded() }
                }
                
                
                connected.cancel()
                if (connectWithinMs != null) awaitConnectOrFail(handle, peerAlias, connectWithinMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PairingSuperseded) {
            
            
            
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

    




    private fun onPairCallFailed(generation: Long, e: Throwable) {
        when (registry.phase(generation)) {
            PairingSessionRegistry.Phase.CONNECTED, PairingSessionRegistry.Phase.UPLOADING ->
                
                
                L.i {
                    "[MigrationV2][P2P][Rx] reverse-pair response lost after the upload started — " +
                        "ignored (${e.javaClass.simpleName})"
                }
            PairingSessionRegistry.Phase.PENDING -> response.completeExceptionally(e)
            null -> L.i { "[MigrationV2][P2P][Rx] reverse-pair failure for a superseded generation — ignored" }
        }
    }

    
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
            
            false -> throw PairingSuperseded()
            true -> Unit
        }
    }

    private fun body(handle: PairingSessionRegistry.Handle, listen: QrPayload) = ReversePairRequest(
        device = ReversePairDevice(
            alias = DeviceNames.local(appContext),
            appVersion = appVersion,
            
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

    
    private fun Long?.clampedConnectWindow(): Long =
        (this ?: DEFAULT_CONNECT_MS).coerceIn(MIN_CONNECT_MS, MAX_CONNECT_MS)

    
    private fun randomPairToken(): String =
        Base64.encodeToString(
            ByteArray(PAIR_TOKEN_BYTES).also { SecureRandom().nextBytes(it) },
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    companion object {
        const val PAIR_TOKEN_BYTES = 32

        
        const val MAX_ADVERTISED_ADDRESSES = 4

        @VisibleForTesting internal const val MIN_CONNECT_MS = 5_000L

        @VisibleForTesting internal const val MAX_CONNECT_MS = 120_000L

        
        @VisibleForTesting internal const val DEFAULT_CONNECT_MS = 30_000L
    }
}
