package com.difft.android.network

import com.difft.android.base.user.NewGlobalConfig
import com.difft.android.network.requests.AddContactorRequestBody
import com.difft.android.network.requests.BindPushTokenRequestBody
import com.difft.android.network.requests.ContactsRequestBody
import com.difft.android.network.requests.ConversationSetRequestBody
import com.difft.android.network.requests.ConversationShareRequestBody
import com.difft.android.network.requests.ConversationShareResponse
import com.difft.android.network.requests.CriticalAlertRequestBodyNew
import com.difft.android.network.requests.GetConversationSetRequestBody
import com.difft.android.network.requests.GetConversationShareRequestBody
import com.difft.android.network.requests.GetConversationShareResponse
import com.difft.android.network.requests.GrayCheckRequestBody
import com.difft.android.network.requests.ProfileRequestBody
import com.difft.android.network.requests.SpeechToTextRequestBody
import com.difft.android.network.responses.AddContactorResponse
import com.difft.android.network.responses.AppVersionResponse
import com.difft.android.network.responses.AuthToken
import com.difft.android.network.responses.ContactsDataResponse
import com.difft.android.network.responses.ConversationSetResponseBody
import com.difft.android.network.responses.CriticalAlertResponse
import com.difft.android.network.responses.GetConversationSetResponseBody
import com.difft.android.network.responses.GrayConfigData
import com.difft.android.network.responses.PendingMessageResponse
import com.difft.android.network.responses.SpeechToTextResponse
import com.difft.android.websocket.api.messages.GetPublicKeysReq
import com.difft.android.websocket.api.messages.GetPublicKeysResp
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Url


interface HttpService {

    @GET
    suspend fun getResponseBody(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @QueryMap params: Map<String, String>
    ): ResponseBody

    @POST("v1/directory/contacts")
    suspend fun fetchContactors(
        @Query("properties") properties: String = "all",
        @Header("Authorization") baseAuth: String,
        @Body body: ContactsRequestBody,
    ): BaseResponse<ContactsDataResponse>

    @POST("v1/directory/contacts")
    suspend fun fetchAllContactors(
        @Query("properties") properties: String = "all",
        @Header("Authorization") baseAuth: String,
    ): BaseResponse<ContactsDataResponse>

    @PUT("v1/authorize/token")
    suspend fun fetchAuthToken(
        @Header("Authorization") baseAuth: String
    ): BaseResponse<AuthToken>

    @POST("v3/friend/ask")
    suspend fun fetchAddContactor(
        @Header("Authorization") token: String,
        @Body body: AddContactorRequestBody
    ): BaseResponse<AddContactorResponse>

    @DELETE("v3/friend/{uid}")
    suspend fun fetchDeleteContact(
        @Path("uid") uid: String,
        @Header("Authorization") token: String
    ): BaseResponse<Any>

    @GET("v1/profile/avatar/attachment")
    suspend fun fetchAvatarAttachmentInfo(
        @Header("Authorization") baseAuth: String
    ): BaseResponse<Any>

    @PUT
    suspend fun fetchUploadAvatar(
        @Url url: String,
        @Body file: RequestBody
    ): ResponseBody

    @PUT("v1/profile")
    suspend fun fetchSetProfile(
        @Header("Authorization") baseAuth: String,
        @Body profileRequestBody: ProfileRequestBody
    ): BaseResponse<Any>

    @PUT("v1/accounts/androidnotify")
    suspend fun fetchBindPushToken(
        @Header("Authorization") baseAuth: String,
        @Query("type") type: String,
        @Body bindPushTokenRequestBody: BindPushTokenRequestBody
    ): BaseResponse<String>

    @POST("v1/conversation/set")
    suspend fun fetchConversationSet(
        @Header("Authorization") baseAuth: String,
        @Body conversationSetRequestBody: ConversationSetRequestBody
    ): BaseResponse<ConversationSetResponseBody>

    @POST("v1/conversation/get")
    suspend fun fetchGetConversationSet(
        @Header("Authorization") baseAuth: String,
        @Body getConversationSetRequestBody: GetConversationSetRequestBody
    ): BaseResponse<GetConversationSetResponseBody>

    @POST("v1/conversationconfig/share")
    suspend fun fetchShareConversationConfig(
        @Header("Authorization")
        authorization: String,
        @Body
        req: GetConversationShareRequestBody
    ): BaseResponse<GetConversationShareResponse>

    @PUT("v1/conversationconfig/share/{conversation}")
    suspend fun updateConversationConfig(
        @Header("Authorization")
        authorization: String,
        @Path(value = "conversation", encoded = false)
        conversations: String,
        @Body
        conversationShareRequestBody: ConversationShareRequestBody
    ): BaseResponse<ConversationShareResponse>

    @PUT("v1/accounts/logout")
    suspend fun fetchLogout(
        @Header("Authorization") baseAuth: String
    ): BaseResponse<Any>

    @DELETE("v1/accounts")
    suspend fun fetchDeleteAccount(
        @Header("Authorization") baseAuth: String
    ): BaseResponse<Any>

    @GET
    suspend fun getNewGlobalConfigs(
        @Url url: String
    ): NewGlobalConfig

    @GET
    suspend fun getAppVersionConfigs(
        @Url url: String
    ): AppVersionResponse

    @GET("/v1/attachments/{attachmentId}")
    suspend fun getDownloadUrl(
        @Header("Authorization") baseAuth: String,
        @Path(value = "attachmentId")
        conversations: String,
    ): BaseResponse<Any>

    @GET("/v1/attachments")
    suspend fun fetchAttachmentInfo(
        @Header("Authorization") baseAuth: String
    ): BaseResponse<Any>

    @PUT("/v1/accounts/activate")
    suspend fun activateDevice(
        @Header("Authorization") baseAuth: String
    ): BaseResponse<Any>

    @DELETE("/v1/messages/{source}/{timestamp}")
    suspend fun removePendingMessage(@Header("Authorization") baseAuth: String, @Path("source") source: String, @Path("timestamp") timestamp: String): BaseResponse<Any>

    @GET("/v1/messages")
    suspend fun getPendingMessage(@Header("Authorization") baseAuth: String): PendingMessageResponse

    @POST("/speech2text/whisperX/transcribe")
    suspend fun voiceToText(
        @Header("Authorization") token: String,
        @Body body: SpeechToTextRequestBody
    ): BaseResponse<SpeechToTextResponse>

    @POST("v3/keys/identity/bulk")
    suspend fun getPublicKeys(
        @Header("Authorization") authorization: String,
        @Body req: GetPublicKeysReq
    ): BaseResponse<GetPublicKeysResp>

    @POST("/chat/v3/messages/criticalAlertNew")
    suspend fun sendCriticalAlertNew(
        @Header("Authorization") baseAuth: String,
        @Body req: CriticalAlertRequestBodyNew
    ): BaseResponse<CriticalAlertResponse>

    @POST("/grayCheck/v1/grayCheck")
    suspend fun grayCheck(
        @Header("Authorization") token: String,
        @Body req: GrayCheckRequestBody
    ): BaseResponse<List<GrayConfigData>?>
}
