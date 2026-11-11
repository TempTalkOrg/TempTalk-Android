package com.difft.android.network.signal

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.session.AuthFailureSink
import com.difft.android.base.session.AuthFailureSource
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
    private val httpClient: ChativeHttpClient,
    
    
    private val authFailureSink: dagger.Lazy<AuthFailureSink>,
) {
    private val deviceApiService: DeviceApiService =
        httpClient.getService(DeviceApiService::class.java)

    
    
    private val gson = Gson()

    




    private suspend fun <T> executeDeviceCall(
        logTag: String,
        apiCall: suspend () -> Response<ResponseBody>,
        onSuccess: (Response<ResponseBody>) -> T,
        reportAuthFailure: Boolean = true,
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
        validateDeviceResponse(code, response, reportAuthFailure) 
    }

    



    suspend fun getNewDeviceVerificationCode(): String {
        return deviceApiService.getDeviceVerificationCode().verificationCode
    }

    



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

    











    suspend fun checkDeviceAuth(logSuccess: Boolean = true) {
        executeDeviceCall(
            logTag = "[DeviceRepository] checkDeviceAuth",
            apiCall = { deviceApiService.getDevices() },
            onSuccess = { response ->
                if (logSuccess) L.i { "[DeviceRepository] checkDeviceAuth: success (${response.code()})" }
                response.body()?.close()
            },
        )
    }

    








    suspend fun checkDeviceAuthAs(basicAuth: String, reportAuthFailure: Boolean, logSuccess: Boolean = true) {
        executeDeviceCall(
            logTag = "[DeviceRepository] checkDeviceAuthAs",
            apiCall = { deviceApiService.getDevicesAs(basicAuth) },
            onSuccess = { response ->
                if (logSuccess) L.i { "[DeviceRepository] event=check_device_auth_as_ok code=${response.code()}" }
                response.body()?.close()
            },
            reportAuthFailure = reportAuthFailure,
        )
    }

    








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

    
    private fun parseDevices(body: String): List<DeviceInfo> {
        if (body.isBlank()) return emptyList()
        return try {
            gson.fromJson(body, DevicesResponse::class.java)?.devices.orEmpty()
        } catch (e: JsonSyntaxException) {
            L.w(e) { "[LinkedDevices] getDevices: malformed body" }
            throw MalformedResponseException("Unable to parse device list")
        }
    }

    




    private fun validateDeviceResponse(
        code: Int,
        response: Response<ResponseBody>,
        reportAuthFailure: Boolean = true,
    ): Nothing {
        when (code) {
            401, 403 -> {
                L.w { "[DeviceRepository] validateDeviceResponse: auth failed ($code)" }
                response.errorBody()?.close()
                
                
                if (reportAuthFailure) authFailureSink.get().onAuthRejected(AuthFailureSource.DEVICE_API)
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
