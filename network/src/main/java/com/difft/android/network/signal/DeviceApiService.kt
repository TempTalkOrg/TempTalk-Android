package com.difft.android.network.signal

import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
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
     * GET /v1/devices/
     * Returns raw Response to allow Repository-level error handling.
     * The @SignalApi client preserves raw HTTP status codes (no HttpClientInterceptor).
     */
    @GET("v1/devices/")
    suspend fun checkDeviceAuth(): Response<ResponseBody>
}

data class DeviceVerificationCodeResponse(
    @SerializedName("verificationCode") val verificationCode: String
)

data class ProvisioningMessageRequest(
    @SerializedName("body") val body: String  // Base64-encoded ciphertext
)
