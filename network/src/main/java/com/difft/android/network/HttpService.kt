package com.difft.android.network

import com.difft.android.base.user.NewGlobalConfig
import com.difft.android.network.requests.AddContactorRequestBody
import com.difft.android.network.requests.BindPushTokenRequestBody
import com.difft.android.network.requests.ContactsRequestBody
import com.difft.android.network.requests.ConversationSetRequestBody
import com.difft.android.network.requests.ConversationShareRequestBody
import com.difft.android.network.requests.ConversationShareResponse
import com.difft.android.network.requests.CriticalAlertRequestBody
import com.difft.android.network.requests.GetConversationSetRequestBody
import com.difft.android.network.requests.GetConversationShareRequestBody
import com.difft.android.network.requests.GetConversationShareResponse
import com.difft.android.network.requests.GetTokenRequestBody
import com.difft.android.network.requests.ProfileRequestBody
import com.difft.android.network.requests.SpeechToTextRequestBody
import com.difft.android.network.responses.AddContactorResponse
import com.difft.android.network.responses.AppVersionResponse
import com.difft.android.network.responses.AuthToken
import com.difft.android.network.responses.ContactsDataResponse
import com.difft.android.network.responses.ConversationSetResponseBody
import com.difft.android.network.responses.GetConversationSetResponseBody
import com.difft.android.network.responses.PendingMessageResponse
import com.difft.android.network.responses.SpeechToTextResponse
import com.difft.android.websocket.api.messages.GetPublicKeysReq
import com.difft.android.websocket.api.messages.GetPublicKeysResp
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
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
    fun getResponseBody(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @QueryMap params: Map<String, String>
    ): Observable<ResponseBody>

    @POST("v1/directory/contacts")
    fun fetchContactors(
        @Query("properties") properties: String = "all",
        @Header("Authorization") baseAuth: String,
        @Body body: ContactsRequestBody,
    ): Single<BaseResponse<ContactsDataResponse>>

    @POST("v1/directory/contacts")
    fun fetchAllContactors(
        @Query("properties") properties: String = "all",
        @Header("Authorization") baseAuth: String,
    ): Single<BaseResponse<ContactsDataResponse>>

    @PUT("v1/authorize/token")
    fun fetchAuthToken(
        @Header("Authorization") baseAuth: String,
        @Body body: GetTokenRequestBody = GetTokenRequestBody(null, null)
    ): Single<BaseResponse<AuthToken>>

    @POST("v3/friend/ask")
    fun fetchAddContactor(
        @Header("Authorization") token: String,
        @Body body: AddContactorRequestBody
    ): Single<BaseResponse<AddContactorResponse>>

    @PUT("v3/friend/ask/{askId}/agree")
    fun fetchAgreeContactRequest(
        @Path("askId") askId: Int,
        @Header("Authorization") token: String
    ): Single<BaseResponse<String>>

    @DELETE("v3/friend/{uid}")
    fun fetchDeleteContact(
        @Path("uid") uid: String,
        @Header("Authorization") token: String
    ): Single<BaseResponse<Any>>

    @GET("v1/profile/avatar/attachment")
    fun fetchAvatarAttachmentInfo(
        @Header("Authorization") baseAuth: String
    ): Single<BaseResponse<Any>>

    @PUT
    fun fetchUploadAvatar(
        @Url url: String,
        @Body file: RequestBody
    ): Single<ResponseBody>

    @PUT("v1/profile")
    fun fetchSetProfile(
        @Header("Authorization") baseAuth: String,
        @Body profileRequestBody: ProfileRequestBody
    ): Single<BaseResponse<Any>>

    @PUT("v1/accounts/androidnotify")
    fun fetchBindPushToken(
        @Header("Authorization") baseAuth: String,
        @Query("type") type: String,
        @Body bindPushTokenRequestBody: BindPushTokenRequestBody
    ): Single<BaseResponse<String>>

    @POST("v1/conversation/set")
    fun fetchConversationSet(
        @Header("Authorization") baseAuth: String,
        @Body conversationSetRequestBody: ConversationSetRequestBody
    ): Single<BaseResponse<ConversationSetResponseBody>>

    @POST("v1/conversation/get")
    fun fetchGetConversationSet(
        @Header("Authorization") baseAuth: String,
        @Body getConversationSetRequestBody: GetConversationSetRequestBody
    ): Single<BaseResponse<GetConversationSetResponseBody>>

    @POST("v1/conversationconfig/share")
    fun fetchShareConversationConfig(
        @Header("Authorization")
        authorization: String,
        @Body
        req: GetConversationShareRequestBody
    ): Single<BaseResponse<GetConversationShareResponse>>

    @PUT("v1/conversationconfig/share/{conversation}")
    fun updateConversationConfig(
        @Header("Authorization")
        authorization: String,
        @Path(value = "conversation", encoded = false)
        conversations: String,
        @Body
        conversationShareRequestBody: ConversationShareRequestBody
    ): Single<BaseResponse<ConversationShareResponse>>

    @PUT("v1/accounts/logout")
    fun fetchLogout(
        @Header("Authorization") baseAuth: String
    ): Single<BaseResponse<Any>>

    @DELETE("v1/accounts")
    fun fetchDeleteAccount(
        @Header("Authorization") baseAuth: String
    ): Single<BaseResponse<Any>>

    @GET
    fun getNewGlobalConfigs(
        @Url url: String
    ): Observable<NewGlobalConfig>

    @GET
    fun getAppVersionConfigs(
        @Url url: String
    ): Observable<AppVersionResponse>

    @GET("/v1/attachments/{attachmentId}")
    fun getDownloadUrl(
        @Header("Authorization") baseAuth: String,
        @Path(value = "attachmentId")
        conversations: String,
    ): Single<BaseResponse<Any>>

    @GET("/v1/attachments")
    fun fetchAttachmentInfo(
        @Header("Authorization") baseAuth: String
    ): Single<BaseResponse<Any>>

    @PUT("/v1/accounts/activate")
    fun activateDevice(
        @Header("Authorization") baseAuth: String
    ): Single<BaseResponse<Any>>

    @DELETE("/v1/messages/{source}/{timestamp}")
    fun removePendingMessage(@Header("Authorization") baseAuth: String, @Path("source") source: String, @Path("timestamp") timestamp: String): Single<BaseResponse<Any>>

    @GET("/v1/messages")
    suspend fun getPendingMessage(@Header("Authorization") baseAuth: String): PendingMessageResponse

    @POST("/speech2text/whisperX/transcribe")
    fun voiceToText(
        @Header("Authorization") token: String,
        @Body body: SpeechToTextRequestBody
    ): Single<BaseResponse<SpeechToTextResponse>>

    @POST("v3/keys/identity/bulk")
    fun getPublicKeys(
        @Header("Authorization") authorization: String,
        @Body req: GetPublicKeysReq
    ): Observable<BaseResponse<GetPublicKeysResp>>

    @POST("/chat/v3/messages/criticalAlert")
    fun sendCriticalAlert(
        @Header("Authorization") baseAuth: String,
        @Body req: CriticalAlertRequestBody
    ): Single<BaseResponse<Any>>
}