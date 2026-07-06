package com.difft.android.setting.proxy

import android.content.Context
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.proxy.ProxyConfigProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ProxyProbe

@InstallIn(SingletonComponent::class)
@Module
abstract class ProxyE2eProbeBindModule {
    @Binds
    abstract fun bindProbe(impl: ProxyE2eProbeImpl): ProxyE2eProbe
}

@InstallIn(SingletonComponent::class)
@Module
object ProxyE2eProbeProvideModule {
    /**
     * 专用探针 client：chative-CA 内层信任（self 自签）+ 不带 token + 关闭 host-failover 拦截器
     * + 走代理工厂。baseUrl 用字面量哨兵（探针恒用 @Url 传完整绝对 URL，baseUrl 不参与路由）。
     */
    @ProxyProbe
    @Provides
    @Singleton
    fun provideProbeClient(
        @ApplicationContext ctx: Context,
        proxyConfigProvider: ProxyConfigProvider,
    ): ChativeHttpClient = ChativeHttpClient(
        applicationContext = ctx,
        // 哨兵占位，绝不要用 urlManager.default：它是计算属性，每次取值都触发 getBestHost()，
        // 且在 @Singleton @Provides 构造期被 eager 求值（牵动 speedtest 协调器）。
        // 探针只用 @Url 绝对 URL，baseUrl 不参与路由，故用恒不可达的字面量即可。
        // Retrofit 仅要求 baseUrl 是带结尾斜杠的合法 HttpUrl —— probe.invalid 语法合法且永不解析。
        baseUrl = "https://probe.invalid/",
        authProvider = null,
        useCustomCa = true,                    // 必须：信任 self 自签 origin 证书（否则 SSLHandshakeException 假阴性）
        removeHeader = true,                   // 不带 token
        connectTimeoutSeconds = 8L,            // 构造器签名是 Long，须用 Long 字面量
        readWriteTimeoutSeconds = 8L,
        useHttpClientInterceptor = false,      // 不可改：关闭 host-failover/改写/token，保「任意响应(含5xx)=成功」
        proxyConfigProvider = proxyConfigProvider,
    )
}
