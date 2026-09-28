package com.difft.android.chat.invite

import com.difft.android.network.BaseResponse
import com.difft.android.network.auth.Auth
import com.difft.android.network.auth.AuthStyle
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

@Auth(AuthStyle.JWT)
interface InviteService {
    @POST("v3/accounts/inviteCode")
    suspend fun getInviteCode(
        @Query("regenerate") regenerate: Int = 0,
        @Query("short") short: Int = 0
    ): BaseResponse<GetInviteCodeResponse>


    @PUT("v3/accounts/querybyInviteCode")
    suspend fun queryByInviteCode(
        @Body body: QueryInviteCodeRequest
    ): BaseResponse<QueryInviteCodeResponse>

    @POST("v3/directory/search")
    suspend fun queryByCustomUid(
        @Body body: QueryCustomUidRequest
    ): BaseResponse<QueryCustomUidResponse>

}
