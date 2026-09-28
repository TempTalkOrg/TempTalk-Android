package com.difft.android.network

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
import com.difft.android.network.requests.SetPriorConversationRequestBody
import com.difft.android.network.requests.SpeechToTextRequestBody
import com.difft.android.base.user.NewGlobalConfig
import com.difft.android.network.auth.Auth
import com.difft.android.network.auth.AuthStyle
import com.difft.android.network.responses.AddContactorResponse
import com.difft.android.network.responses.EncryptedGlobalConfigResponse
import com.difft.android.network.responses.AppVersionResponse
import com.difft.android.network.responses.AuthToken
import com.difft.android.network.responses.ContactsDataResponse
import com.difft.android.network.responses.ConversationSetResponseBody
import com.difft.android.network.responses.CriticalAlertResponse
import com.difft.android.network.responses.DeletedRecordDto
import com.difft.android.network.responses.GetConversationSetResponseBody
import com.difft.android.network.responses.FavoritesPutRequest
import com.difft.android.network.responses.FavoritesResponse
import com.difft.android.network.responses.GifListResponse
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
import retrofit2.http.Streaming
import retrofit2.http.Url


interface HttpService {

    /**
     * Lightweight probe used only to anchor [com.difft.android.base.utils.time.ServerTimeProvider]:
     * the response is a BaseResponse envelope whose outer serverTimestamp is captured by the
     * ServerTimeCaptureConverterFactory hook. The body payload itself is unused here.
     */
    @Auth(AuthStyle.NONE)
    @GET("v1/health")
    suspend fun health(): BaseResponse<Any>

    @Auth(AuthStyle.NONE)
    @GET
    suspend fun getResponseBody(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @QueryMap params: Map<String, String>
    ): ResponseBody

    /**
     * Streaming GET: @Streaming keeps Retrofit from buffering the whole body into memory,
     * so contentLength() reflects the response header and byteStream() reads incrementally.
     * Required for real download progress and to avoid OOM on large files (e.g. APK upgrades).
     */
    @Auth(AuthStyle.NONE)
    @Streaming
    @GET
    suspend fun getResponseBodyStreaming(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @QueryMap params: Map<String, String>
    ): ResponseBody

    @Auth(AuthStyle.BASIC)
    @POST("v1/directory/contacts")
    suspend fun fetchContactors(
        @Query("properties") properties: String = "all",
        @Body body: ContactsRequestBody,
    ): BaseResponse<ContactsDataResponse>

    @Auth(AuthStyle.BASIC)
    @POST("v1/directory/contacts")
    suspend fun fetchAllContactors(
        @Query("properties") properties: String = "all",
    ): BaseResponse<ContactsDataResponse>

    /**
     * The refresh itself. Runs before `baseAuth` is persisted during login, so the caller supplies
     * the credential and the interceptor must not touch it.
     */
    @Auth(AuthStyle.EXPLICIT)
    @PUT("v1/authorize/token")
    suspend fun fetchAuthToken(
        @Header("Authorization") baseAuth: String
    ): BaseResponse<AuthToken>

    @Auth(AuthStyle.JWT)
    @POST("v3/friend/ask")
    suspend fun fetchAddContactor(
        @Body body: AddContactorRequestBody
    ): BaseResponse<AddContactorResponse>

    @Auth(AuthStyle.JWT)
    @DELETE("v3/friend/{uid}")
    suspend fun fetchDeleteContact(
        @Path("uid") uid: String
    ): BaseResponse<Any>

    /**
     * Fetch the weak-contact pending-removal set (bare array, unpaged, 15-day window).
     * serverTimestamp comes from the outer BaseResponse.
     * Note: different path from fetchDeleteContact above (v3/friend/{uid}, which unfriends).
     */
    @Auth(AuthStyle.JWT)
    @GET("v3/friend/deletedRecords")
    suspend fun fetchDeletedRecords(): BaseResponse<List<DeletedRecordDto>>

    /**
     * Remove a weak contact immediately. Idempotent; on success the server pushes changeType=1 to sync other devices.
     * Note: different path from fetchDeleteContact above (v3/friend/{uid}, which unfriends).
     */
    @Auth(AuthStyle.JWT)
    @DELETE("v3/friend/deletedRecords/{uid}")
    suspend fun deleteDeletedRecord(
        @Path("uid") uid: String
    ): BaseResponse<Any>

    @Auth(AuthStyle.BASIC)
    @GET("v1/profile/avatar/attachment")
    suspend fun fetchAvatarAttachmentInfo(): BaseResponse<Any>

    @Auth(AuthStyle.NONE)
    @PUT
    suspend fun fetchUploadAvatar(
        @Url url: String,
        @Body file: RequestBody
    ): ResponseBody

    @Auth(AuthStyle.BASIC)
    @PUT("v1/profile")
    suspend fun fetchSetProfile(
        @Body profileRequestBody: ProfileRequestBody
    ): BaseResponse<Any>

    @Auth(AuthStyle.BASIC)
    @PUT("v1/accounts/androidnotify")
    suspend fun fetchBindPushToken(
        @Query("type") type: String,
        @Body bindPushTokenRequestBody: BindPushTokenRequestBody
    ): BaseResponse<String>

    @Auth(AuthStyle.BASIC)
    @POST("v1/conversation/set")
    suspend fun fetchConversationSet(
        @Body conversationSetRequestBody: ConversationSetRequestBody
    ): BaseResponse<ConversationSetResponseBody>

    @Auth(AuthStyle.BASIC)
    @POST("v1/conversation/get")
    suspend fun fetchGetConversationSet(
        @Body getConversationSetRequestBody: GetConversationSetRequestBody
    ): BaseResponse<GetConversationSetResponseBody>

    @Auth(AuthStyle.JWT)
    @POST("v1/conversationconfig/share")
    suspend fun fetchShareConversationConfig(
        @Body
        req: GetConversationShareRequestBody
    ): BaseResponse<GetConversationShareResponse>

    @Auth(AuthStyle.JWT)
    @PUT("v1/conversationconfig/share/{conversation}")
    suspend fun updateConversationConfig(
        @Path(value = "conversation", encoded = false)
        conversations: String,
        @Body
        conversationShareRequestBody: ConversationShareRequestBody
    ): BaseResponse<ConversationShareResponse>

    @Auth(AuthStyle.BASIC)
    @PUT("v1/accounts/logout")
    suspend fun fetchLogout(): BaseResponse<Any>

    @Auth(AuthStyle.BASIC)
    @DELETE("v1/accounts")
    suspend fun fetchDeleteAccount(): BaseResponse<Any>

    @Auth(AuthStyle.NONE)
    @GET
    suspend fun getEncryptedGlobalConfig(
        @Url url: String
    ): EncryptedGlobalConfigResponse

    @Auth(AuthStyle.NONE)
    @GET
    suspend fun getGlobalConfig(
        @Url url: String
    ): NewGlobalConfig

    @Auth(AuthStyle.NONE)
    @GET
    suspend fun getAppVersionConfigs(
        @Url url: String
    ): AppVersionResponse

    @Auth(AuthStyle.BASIC)
    @GET("/v1/attachments/{attachmentId}")
    suspend fun getDownloadUrl(
        @Path(value = "attachmentId")
        conversations: String,
    ): BaseResponse<Any>

    @Auth(AuthStyle.BASIC)
    @GET("/v1/attachments")
    suspend fun fetchAttachmentInfo(): BaseResponse<Any>

    @Auth(AuthStyle.BASIC)
    @PUT("/v1/accounts/activate")
    suspend fun activateDevice(): BaseResponse<Any>

    @Auth(AuthStyle.BASIC)
    @DELETE("/v1/messages/{source}/{timestamp}")
    suspend fun removePendingMessage(@Path("source") source: String, @Path("timestamp") timestamp: String): BaseResponse<Any>

    @Auth(AuthStyle.BASIC)
    @GET("/v1/messages")
    suspend fun getPendingMessage(): PendingMessageResponse

    /**
     * Reports the conversation the user is currently viewing so its backlog is delivered first.
     * Root path like the other `/v1/messages` endpoints above (the Chat client's base carries `/chat/`).
     */
    @Auth(AuthStyle.BASIC)
    @POST("/v1/messages/setPriorConversation")
    suspend fun setPriorConversation(
        @Body body: SetPriorConversationRequestBody
    ): BaseResponse<Any>

    @Auth(AuthStyle.JWT)
    @POST("/speech2text/whisperX/transcribe")
    suspend fun voiceToText(
        @Body body: SpeechToTextRequestBody
    ): BaseResponse<SpeechToTextResponse>

    @Auth(AuthStyle.JWT)
    @POST("v3/keys/identity/bulk")
    suspend fun getPublicKeys(
        @Body req: GetPublicKeysReq
    ): BaseResponse<GetPublicKeysResp>

    /** A `v3` route that authenticates on Basic, not on the account JWT the other `v3` routes use. */
    @Auth(AuthStyle.BASIC)
    @POST("/chat/v3/messages/criticalAlertNew")
    suspend fun sendCriticalAlertNew(
        @Body req: CriticalAlertRequestBodyNew
    ): BaseResponse<CriticalAlertResponse>

    @Auth(AuthStyle.JWT)
    @POST("/grayCheck/v1/grayCheck")
    suspend fun grayCheck(
        @Body req: GrayCheckRequestBody
    ): BaseResponse<List<GrayConfigData>?>

    /**
     * GIF trending list. Full URL is supplied via [url] (built from UrlManager.gifs +
     * "v1/gifs/trending") so the request rides the `/gifs/` proxy host.
     */
    @Auth(AuthStyle.JWT)
    @GET
    suspend fun getGifsTrending(
        @Url url: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Query("next") next: String?,
        @Query("rating") rating: String? = null
    ): BaseResponse<GifListResponse>

    /** GIF search list. Full URL via [url] (UrlManager.gifs + "v1/gifs/search"). */
    @Auth(AuthStyle.JWT)
    @GET
    suspend fun getGifsSearch(
        @Url url: String,
        @Query("q") q: String?,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Query("next") next: String?,
        @Query("rating") rating: String? = null
    ): BaseResponse<GifListResponse>

    /**
     * GIF favorites (zero-knowledge). Full URL via [url] (UrlManager.gifs + "v1/gifs/favorites")
     * so it rides the `/gifs/` proxy host.
     */
    @Auth(AuthStyle.JWT)
    @GET
    suspend fun getFavorites(
        @Url url: String
    ): BaseResponse<FavoritesResponse>

    /** Optimistic-lock PUT of the whole favorites list. URL via UrlManager.gifs + "v1/gifs/favorites". */
    @Auth(AuthStyle.JWT)
    @PUT
    suspend fun putFavorites(
        @Url url: String,
        @Body body: FavoritesPutRequest
    ): BaseResponse<FavoritesResponse>
}
