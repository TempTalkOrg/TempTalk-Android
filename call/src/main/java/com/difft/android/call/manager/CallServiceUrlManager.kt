package com.difft.android.call.manager

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通话服务URL管理器
 * 负责管理通话服务URL的获取和缓存
 * 
 * 主要职责：
 * - 从服务器获取通话服务URL
 * - 缓存服务URL列表
 * - 提供获取缓存URL的方法
 */
@Singleton
class CallServiceUrlManager @Inject constructor() {
    
    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        @ChativeHttpClientModule.Call
        fun callHttpClient(): ChativeHttpClient
    }
    
    private val callHttpService by lazy {
        val callHttpClient = EntryPointAccessors.fromApplication<EntryPoint>(ApplicationHelper.instance).callHttpClient()
        callHttpClient.getService(LCallHttpService::class.java)
    }
    
    // 缓存的服务URL列表
    private val callServiceUrls = mutableListOf<String>()
    
    /**
     * 从服务器获取通话服务URL并缓存
     * 
     * @return 服务URL列表，如果获取失败则返回空列表
     */
    suspend fun fetchCallServiceUrlAndCache(): List<String> {
        try {
            val token = SecureSharedPrefsUtil.getToken()
            if(token.isEmpty()) return emptyList()
            val response = withContext(Dispatchers.IO) {
                callHttpService.getServiceUrl(SecureSharedPrefsUtil.getToken()).await()
            }
            return if (response.status == 0) {
                val serviceUrls = response.data?.serviceUrls
                if (!serviceUrls.isNullOrEmpty()) {
                    synchronized(callServiceUrls) {
                        callServiceUrls.clear()
                        callServiceUrls.addAll(serviceUrls)
                    }
                    L.i { "[Call] CallServiceUrlManager Fetched and cached ${serviceUrls.size} service URLs" }
                    serviceUrls
                } else {
                    L.w { "[Call] CallServiceUrlManager Service URLs list is empty" }
                    emptyList()
                }
            } else {
                L.e { "[Call] CallServiceUrlManager Failed to fetch service URLs, status: ${response.status}" }
                emptyList()
            }
        } catch (e: Exception) {
            L.e { "[Call] CallServiceUrlManager fetchCallServiceUrl failed: ${e.message}" }
            return emptyList()
        }
    }
    
    /**
     * 获取缓存的服务URL列表
     * 
     * @return 服务URL列表的副本，如果缓存为空则返回空列表
     */
    fun getCallServiceUrl(): List<String> {
        return synchronized(callServiceUrls) {
            callServiceUrls.toList()
        }
    }
    
    /**
     * 清空缓存的服务URL列表
     */
    fun clearCache() {
        synchronized(callServiceUrls) {
            callServiceUrls.clear()
            L.d { "[Call] CallServiceUrlManager Cleared service URL cache" }
        }
    }
    
    /**
     * 检查缓存是否为空
     * 
     * @return true 如果缓存为空，false 否则
     */
    fun isCacheEmpty(): Boolean {
        return synchronized(callServiceUrls) {
            callServiceUrls.isEmpty()
        }
    }
}

