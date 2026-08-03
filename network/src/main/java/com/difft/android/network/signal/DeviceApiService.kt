package com.difft.android.network.signal

import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path

interface DeviceApiService {

    /** GET /v1/devices/provisioning/code */
    @GET("v1/devices/provisioning/code")
    suspend fun getDeviceVerificationCode(): DeviceVerificationCodeResponse

    /** PUT /v1/provisioning/{destination} */
    @PUT("v1/provisioning/{destination}")
    suspend fun sendProvisioningMessage(
        @Path("destination") destination: String,
        @Body body: ProvisioningMessageRequest
    )

    /**
     * GET /v1/devices/ — returns {"devices":[...]}. Raw Response so the Repository maps status
     * codes itself; the @SignalApi client preserves raw HTTP status.
     */
    @GET("v1/devices/")
    suspend fun getDevices(): Response<ResponseBody>

    /** DELETE /v1/devices/{deviceId}. Server contract unverified. */
    @DELETE("v1/devices/{deviceId}")
    suspend fun removeDevice(@Path("deviceId") deviceId: Int): Response<ResponseBody>
}

data class DeviceVerificationCodeResponse(
    @SerializedName("verificationCode") val verificationCode: String
)

data class ProvisioningMessageRequest(
    @SerializedName("body") val body: String  // Base64-encoded ciphertext
)
