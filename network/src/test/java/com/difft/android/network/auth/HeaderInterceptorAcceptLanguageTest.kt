package com.difft.android.network.auth

import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HeaderInterceptor
import com.difft.android.network.HiltEntryPointRule
import com.difft.android.network.TestScopeApplication
import okhttp3.Request
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals







@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, qualifiers = "en-rUS")
class HeaderInterceptorAcceptLanguageTest {

    @get:Rule
    val hiltEntryPoints = HiltEntryPointRule()

    private val interceptor = HeaderInterceptor(
        authProvider = object : ChativeHttpClient.AuthProvider {
            override fun provideAuth(): String = "Basic dGVzdA=="
        },
        tokenProvider = lazyOf<AuthTokenProvider>(FakeAuthTokenProvider()),
        handleRejection = true,
        failFastOnMissingDeclaration = false,
    )

    @Test
    fun `the next request after a system language change carries the new language`() {
        assertEquals("en", acceptLanguage())

        RuntimeEnvironment.setQualifiers("zh-rCN")

        assertEquals("zh", acceptLanguage())
    }

    private fun acceptLanguage(): String? {
        val (chain, sent) = chainOf(Request.Builder().url("https://chat.example.test/v1/ping").build(), 200)
        interceptor.intercept(chain)
        return sent.single.headers["Accept-Language"]
    }
}
