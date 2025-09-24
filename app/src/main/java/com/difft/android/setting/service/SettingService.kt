package com.difft.android.setting.service

import com.difft.android.network.BaseResponse
import com.difft.android.setting.data.CheckUpdateResponse
import com.difft.android.setting.data.GetProfileResponse
import com.difft.android.setting.data.ResetPasscodeRequestBody
import com.difft.android.setting.data.SetStatusRequestBody
import io.reactivex.rxjava3.core.Single
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Query

interface SettingService {
    @GET("v3/directory/profile")
    fun getProfile(
        @Header("Authorization") token: String,
    ): Single<BaseResponse<GetProfileResponse>>


    @PUT("v3/directory/profile")
    fun setProfile(
        @Header("Authorization") token: String,
        @Body body: SetStatusRequestBody
    ): Single<BaseResponse<GetProfileResponse>>

    @GET("v3/upgrade/android/check")
    fun checkUpdate(
        @Header("Authorization") token: String,
        @Query("version") version: String
    ): Single<BaseResponse<CheckUpdateResponse>>
}