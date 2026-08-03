package com.difft.android.network.signal

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.Base64
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.websocket.api.messages.multidevice.DeviceInfo
import com.difft.android.websocket.api.messages.multidevice.DevicesResponse
import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import com.difft.android.websocket.api.push.exceptions.MalformedResponseException
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.push.exceptions.NotFoundException
import com.difft.android.websocket.api.push.exceptions.PushNetworkException
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
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

    // Self-owned Gson for the device-list body. Coexists with JsonUtil/Jackson, which parses the
    // SocketResponse error body — different bodies, do not unify.
    private val gson = Gson()

    /**
     * Shared HTTP skeleton for the device endpoints: wraps [IOException] as [PushNetworkException],
     * treats 200/204 as success (handled by [onSuccess]), and routes every other code through
     * [validateDeviceResponse] (which always throws). [logTag] carries per-call context.
     */
    private suspend fun <T> executeDeviceCall(
        logTag: String,
        apiCall: suspend () -> Response<ResponseBody>,
        onSuccess: (Response<ResponseBody>) -> T,
    ): T {
        val response = try {
            apiCall()
        } catch (e: IOException) {
            L.w(e) { "$logTag: network error" }
            throw PushNetworkException(e)
        }
        val code = response.code()
        if (code == 200 || code == 204) {
            return onSuccess(response)
        }
        L.w { "$logTag: error code=$code" }
        validateDeviceResponse(code, response) // throws
    }

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
     * Auth probe against the devices endpoint (used by RecentChatFragment.checkDevices()).
     *
     * @throws AuthorizationFailedException on 401/403 (triggers logout at caller)
     * @throws AccountOfflineException on 404 with status 10105/10110
     * @throws NotFoundException on other 404
     * @throws PushNetworkException on network failure
     */
    suspend fun checkDeviceAuth() {
        executeDeviceCall(
            logTag = "[DeviceRepository] checkDeviceAuth",
            apiCall = { deviceApiService.getDevices() },
            onSuccess = { response ->
                L.i { "[DeviceRepository] checkDeviceAuth: success (${response.code()})" }
                response.body()?.close()
            },
        )
    }

    /**
     * Fetches only SECONDARY devices (the primary id==1 is filtered here, single owner). Callers
     * must not re-filter or assume the primary is present.
     *
     * @throws AuthorizationFailedException on 401/403 (caller logs out)
     * @throws AccountOfflineException / NotFoundException on 404
     * @throws MalformedResponseException on a 200 with an unparseable body
     * @throws PushNetworkException on network failure
     */
    suspend fun getDevices(): List<DeviceInfo> = executeDeviceCall(
        logTag = "[LinkedDevices] getDevices",
        apiCall = { deviceApiService.getDevices() },
        onSuccess = { response ->
            val body = response.body()?.use { it.string() }.orEmpty()
            val all = parseDevices(body)
            val secondary = all.filter { it.id != DEFAULT_DEVICE_ID }
            L.i { "[LinkedDevices] getDevices: success total=${all.size} secondary=${secondary.size}" }
            secondary
        },
    )

    /**
     * Unlink a secondary device. Server contract unverified; 404 is treated as failure until the
     * backend confirms.
     *
     * @throws IllegalArgumentException if deviceId is the primary (never reachable via UI)
     * @throws AuthorizationFailedException on 401/403 (caller logs out)
     * @throws NotFoundException / AccountOfflineException on 404
     * @throws PushNetworkException on network failure
     */
    suspend fun removeDevice(deviceId: Int) {
        require(deviceId != DEFAULT_DEVICE_ID) {
            "[LinkedDevices] refusing to remove primary device id=$deviceId"
        }
        executeDeviceCall(
            logTag = "[LinkedDevices] removeDevice id=$deviceId",
            apiCall = { deviceApiService.removeDevice(deviceId) },
            onSuccess = { response ->
                L.i { "[LinkedDevices] removeDevice: success id=$deviceId code=${response.code()}" }
                response.body()?.close()
            },
        )
    }

    /** Parse {"devices":[...]}. Blank → empty; malformed → MalformedResponseException. */
    private fun parseDevices(body: String): List<DeviceInfo> {
        if (body.isBlank()) return emptyList()
        return try {
            gson.fromJson(body, DevicesResponse::class.java)?.devices.orEmpty()
        } catch (e: JsonSyntaxException) {
            L.w(e) { "[LinkedDevices] getDevices: malformed body" }
            throw MalformedResponseException("Unable to parse device list")
        }
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
