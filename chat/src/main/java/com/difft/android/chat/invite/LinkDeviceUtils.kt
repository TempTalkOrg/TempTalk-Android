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






@Singleton
class LinkDeviceUtils @Inject constructor(
    private val encryptionDataManager: EncryptionDataManager,
    private val deviceRepository: DeviceRepository,
) {

    



    suspend fun linkDevice(ephemeralId: String, publicKeyEncoded: String): Result<Unit> = try {
        
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
