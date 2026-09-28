package com.difft.android.chat.invite

import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.cryptonew.EncryptionDataManager
import com.difft.android.network.signal.DeviceRepository
import kotlin.coroutines.cancellation.CancellationException
import org.signal.libsignal.protocol.ecc.ECPublicKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Links a Desktop that showed a `tsdevice:` provisioning QR to this account. The confirmation UI
 * lives on the sign-in page that reads the QR (it decides about syncing history at the same time);
 * this class only performs the link itself.
 */
@Singleton
class LinkDeviceUtils @Inject constructor(
    private val encryptionDataManager: EncryptionDataManager,
    private val deviceRepository: DeviceRepository,
) {

    /**
     * Registers the scanned Desktop as a linked device. Returns the failure so the caller can show
     * its message; a coroutine cancellation is rethrown, never swallowed.
     */
    suspend fun linkDevice(ephemeralId: String, publicKeyEncoded: String): Result<Unit> = try {
        // Retrofit suspend funs handle threading; no withContext(IO) needed
        val verificationCode = deviceRepository.getNewDeviceVerificationCode()
        val publicKey = ECPublicKey(Base64.decode(publicKeyEncoded, Base64.DEFAULT), 0)
        val aciIdentityKeyPair = encryptionDataManager.getAciIdentityKey()
        deviceRepository.addDevice(
            ephemeralId,
            publicKey,
            aciIdentityKeyPair,
            verificationCode,
            globalServices.myId,
        )
        L.i { "[LinkDeviceUtils] linked device via provisioning id" }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        L.w { "[LinkDeviceUtils] addDevice error: ${e.stackTraceToString()}" }
        Result.failure(e)
    }
}
