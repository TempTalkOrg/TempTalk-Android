package com.difft.android.login.repo

import com.difft.android.login.data.BindAccountResponse
import com.difft.android.login.service.BindEmailService
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import io.reactivex.rxjava3.core.Single
import javax.inject.Inject

class BindRepo @Inject constructor() {

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var httpClient: ChativeHttpClient

    private val bindService by lazy {
        httpClient.getService(BindEmailService::class.java)
    }

    fun verifyEmail(basicAuth: String, email: String, nonce: String?): Single<BaseResponse<BindAccountResponse>> {
        val params = mutableMapOf("email" to email)
        nonce?.let { params["nonce"] = it }
        return bindService.verifyEmail(basicAuth, params)
    }

    fun verifyEmailCode(basicAuth: String, emailCode: String, nonce: String?): Single<BaseResponse<Any>> {
        val params = mutableMapOf("verificationCode" to emailCode)
        nonce?.let { params["nonce"] = it }
        return bindService.verifyEmailCode(basicAuth, params)
    }

    fun verifyPhone(basicAuth: String, phone: String, nonce: String?): Single<BaseResponse<BindAccountResponse>> {
        val params = mutableMapOf("phone" to phone)
        nonce?.let { params["nonce"] = it }
        return bindService.verifyPhone(basicAuth, params)
    }

    fun verifyPhoneCode(basicAuth: String, phone: String, code: String, nonce: String?): Single<BaseResponse<Any>> {
        val params = mutableMapOf("verificationCode" to code)
        params["phone"] = phone
        nonce?.let { params["nonce"] = it }
        return bindService.verifyPhoneCode(basicAuth, params)
    }
}