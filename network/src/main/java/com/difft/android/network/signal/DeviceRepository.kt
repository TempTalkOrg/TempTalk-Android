package com.difft.android.network.signal

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.Base64
import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.push.exceptions.NotFoundException
import com.difft.android.websocket.api.push.exceptions.PushNetworkException
import com.difft.android.websocket.internal.crypto.PrimaryProvisioningCipher
import com.difft.android.websocket.internal.push.SocketResponse
import com.difft.android.websocket.internal.push.exceptions.AccountOfflineException
import com.difft.android.websocket.internal.util.JsonUtil
import com.google.protobuf.ByteString
import okhttp3.ResponseBody
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.whispersystems.signalservice.internal.push.ProvisioningProtos
import retrofit2.Response
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepository @Inject constructor(
    @param:ChativeHttpClientModule.SignalApi
    private val httpClient: ChativeHttpClient
) {
    private val deviceApiService: DeviceApiService =
        httpClient.getService(DeviceApiService::class.java)

    /**
     * Get a new device verification code for linking.
     * Replaces: SignalServiceAccountManager.getNewDeviceVerificationCode()
     */
    suspend fun getNewDeviceVerificationCode(): String {
        return deviceApiService.getDeviceVerificationCode().verificationCode
    }

    /**
     * Link a new device by sending provisioning message.
     * Replaces: SignalServiceAccountManager.addDevice()
     */
    suspend fun addDevice(
        deviceIdentifier: String,
        deviceKey: ECPublicKey,
        aciIdentityKeyPair: IdentityKeyPair,
        verificationCode: String,
        userId: String
    ) {
        val cipher = PrimaryProvisioningCipher(deviceKey)
        val message = ProvisioningProtos.ProvisionMessage.newBuilder()
            .setAciIdentityKeyPrivate(
                ByteString.copyFrom(aciIdentityKeyPair.privateKey.serialize())
            )
            .setNumber(userId)
            .setProvisioningCode(verificationCode)
            .build()

        val ciphertext = cipher.encrypt(message)
        val body = ProvisioningMessageRequest(Base64.encodeBytes(ciphertext))
        deviceApiService.sendProvisioningMessage(deviceIdentifier, body)
    }

    /**
     * Check device auth by calling the devices endpoint.
     * Replaces: SignalServiceAccountManager.getDevices()
     * used by RecentChatFragment.checkDevices()
     *
     * The @SignalApi client preserves raw HTTP status codes.
     * Error mapping mirrors PushServiceSocket.validateResponse() exactly:
     * - 401/403 -> AuthorizationFailedException
     * - 404 with status 10105/10110 -> AccountOfflineException
     * - 404 other -> NotFoundException
     *
     * @throws AuthorizationFailedException on 401 or 403 (triggers logout at caller)
     * @throws AccountOfflineException on 404 with status 10105 or 10110
     * @throws NotFoundException on 404 with other body
     * @throws IOException on network failure
     */
    suspend fun checkDeviceAuth() {
        val response = try {
            deviceApiService.checkDeviceAuth()
        } catch (e: IOException) {
            L.w(e) { "[DeviceRepository] checkDeviceAuth: network error" }
            throw PushNetworkException(e)
        }

        val code = response.code()
        if (code == 200 || code == 204) {
            L.i { "[DeviceRepository] checkDeviceAuth: success ($code)" }
            response.body()?.close()
            return
        }

        L.w { "[DeviceRepository] checkDeviceAuth: error $code" }
        validateDeviceResponse(code, response)
    }

    /**
     * Maps HTTP error codes to domain exceptions for device endpoints.
     * Directly mirrors the subset of PushServiceSocket.validateResponse()
     * relevant to device endpoints.
     */
    private fun validateDeviceResponse(
        code: Int,
        response: Response<ResponseBody>
    ): Nothing {
        when (code) {
            401, 403 -> {
                L.w { "[DeviceRepository] validateDeviceResponse: auth failed ($code)" }
                response.errorBody()?.close()
                throw AuthorizationFailedException(code, "Authorization failed!")
            }
            404 -> throw parseOfflineOrNotFound(response)
        }
        L.w { "[DeviceRepository] validateDeviceResponse: unhandled code $code" }
        response.errorBody()?.close()
        throw NonSuccessfulResponseCodeException(code, "Bad response: $code")
    }

    private fun parseOfflineOrNotFound(response: Response<ResponseBody>): NonSuccessfulResponseCodeException {
        val bodyString = try { response.errorBody()?.string() } catch (_: Exception) { null }
            ?: return NotFoundException("Not found")
        return try {
            val socketResponse = JsonUtil.fromJson(bodyString, SocketResponse::class.java)
            if (socketResponse != null && socketResponse.status in setOf(10105, 10110)) {
                L.w { "[DeviceRepository] parseOfflineOrNotFound: account offline, status=${socketResponse.status}" }
                AccountOfflineException(socketResponse.status, socketResponse.reason)
            } else {
                L.d { "[DeviceRepository] parseOfflineOrNotFound: 404, body=$bodyString" }
                NotFoundException("Not found")
            }
        } catch (_: Exception) {
            L.d { "[DeviceRepository] parseOfflineOrNotFound: failed to parse 404 body" }
            NotFoundException("Not found")
        }
    }
}
