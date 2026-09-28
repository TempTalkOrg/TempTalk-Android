package com.difft.android.setting.service

import com.difft.android.network.BaseResponse
import com.difft.android.network.auth.Auth
import com.difft.android.network.auth.AuthStyle
import com.difft.android.setting.data.CheckUpdateResponse
import com.difft.android.setting.data.GetProfileResponse
import com.difft.android.setting.data.SetStatusRequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Query

@Auth(AuthStyle.JWT)
interface SettingService {
    @GET("v3/directory/profile")
    suspend fun getProfile(): BaseResponse<GetProfileResponse>


    @PUT("v3/directory/profile")
    suspend fun setProfile(
        @Body body: SetStatusRequestBody
    ): BaseResponse<GetProfileResponse>

    /**
     * [channel] is `BuildConfig.APP_CHANNEL`: the server keeps one upgrade entry per channel
     * (`official` for this package's direct downloads, `google`, `fdroid`), and `default` for the
     * retired package, which never sends the parameter.
     */
    @GET("v3/upgrade/android/check")
    suspend fun checkUpdate(
        @Query("version") version: String,
        @Query("channel") channel: String
    ): BaseResponse<CheckUpdateResponse>
}