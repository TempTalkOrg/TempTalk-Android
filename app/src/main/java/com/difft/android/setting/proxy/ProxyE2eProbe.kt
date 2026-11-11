package com.difft.android.setting.proxy


interface ProxyE2eProbe {
    
    suspend fun probe(): Boolean
}
