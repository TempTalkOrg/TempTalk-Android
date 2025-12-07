package com.difft.android.network

import android.content.Context
import com.difft.android.network.ca.OfficialSSLSocketFactoryCreator
import com.google.gson.Gson
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit


class ChativeHttpClient(
    private val applicationContext: Context,
    baseUrl: String,
    authProvider: AuthProvider?,
    useCustomCa: Boolean = true,
    removeHeader: Boolean = false,
    connectTimeoutSeconds: Long = 15,
    readWriteTimeoutSeconds: Long = 15
) {

    interface AuthProvider {
        fun provideAuth(): String?
    }

    private val gson = Gson()

    private val customConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
        .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3) // 指定TLS版本为TLS 1.2  TLS 1.2
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .apply {
            if (removeHeader) {
                addInterceptor(NoHeaderInterceptor())
            } else {
                addInterceptor(HeaderInterceptor(authProvider))
            }
            addInterceptor(HttpClientInterceptor())
            if (BuildConfig.DEBUG) {
                //如果想使用抓包工具获取接口数据，可以开启这个
//                val manager = TrustAllSSLSocketFactory.TrustAllManager()
//                sslSocketFactory(
//                    TrustAllSSLSocketFactory.create(manager),
//                    manager
//                )
                val loggingInterceptor = HttpLoggingInterceptor()
                loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY)
                addInterceptor(loggingInterceptor)
            }
            if (useCustomCa) {
                val officialSSLSocketFactoryCreator = OfficialSSLSocketFactoryCreator(applicationContext)
                val socketFactory = officialSSLSocketFactoryCreator.socketFactory
                val trustManager = officialSSLSocketFactoryCreator.trustManager
                sslSocketFactory(socketFactory, trustManager)
            }
        }
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readWriteTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(readWriteTimeoutSeconds, TimeUnit.SECONDS)
        .connectionSpecs(listOf(customConnectionSpec))
        .build()
    private val retrofit: Retrofit = Retrofit.Builder()
        .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
        .addConverterFactory(ScalarsConverterFactory.create())
        .addConverterFactory(GsonConverterFactory.create(gson))
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .build()

    val httpService = getService(HttpService::class.java)
    fun <T> getService(service: Class<T>): T = retrofit.create(service)
}