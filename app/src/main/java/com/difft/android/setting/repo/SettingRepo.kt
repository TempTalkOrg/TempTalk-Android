package com.difft.android.setting.repo

import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.setting.data.CheckUpdateResponse
import com.difft.android.setting.data.GetProfileResponse
import com.difft.android.setting.data.SetStatusRequestBody
import com.difft.android.setting.service.SettingService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingRepo @Inject constructor() {
    @ChativeHttpClientModule.Default
    @Inject
    lateinit var httpClient: ChativeHttpClient

    private val settingService by lazy {
        httpClient.getService(SettingService::class.java)
    }

    suspend fun getProfile(): BaseResponse<GetProfileResponse> = settingService.getProfile()

    suspend fun setProfile(searchByPhone: Int? = null, searchByEmail: Int? = null, searchByCustomUid: Int? = null, customUid: String? = null): BaseResponse<GetProfileResponse> = settingService.setProfile(SetStatusRequestBody(searchByPhone, searchByEmail, searchByCustomUid, customUid))

    suspend fun checkUpdate(version: String, channel: String): BaseResponse<CheckUpdateResponse> = settingService.checkUpdate(version, channel)
}