package com.difft.android.lint

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

class AuthStyleDetectorTest {

    /** T41 — a Retrofit endpoint with no declaration anywhere. */
    @Test
    fun `flags an endpoint that declares no auth style`() {
        lint()
            .files(
                AUTH_STUB,
                RETROFIT_STUB,
                kotlin(
                    "src/com/difft/android/network/UndeclaredService.kt",
                    """
                    package com.difft.android.network

                    import com.difft.android.network.auth.Auth
                    import com.difft.android.network.auth.AuthStyle
                    import retrofit2.http.GET
                    import retrofit2.http.POST

                    interface UndeclaredService {
                        @GET("v1/thing")
                        suspend fun getThing(): String

                        @Auth(AuthStyle.BASIC)
                        @POST("v1/thing")
                        suspend fun postThing(): String

                        fun notAnEndpoint(): String
                    }
                    """,
                ).indented(),
            )
            .issues(AuthStyleDetector.AUTH_STYLE_MISSING)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
            .expectContains("getThing declares no auth style")
    }

    /** T41 — an interface-level declaration covers every method on it. */
    @Test
    fun `accepts endpoints covered by an interface-level declaration`() {
        lint()
            .files(
                AUTH_STUB,
                RETROFIT_STUB,
                kotlin(
                    "src/com/difft/android/network/UniformService.kt",
                    """
                    package com.difft.android.network

                    import com.difft.android.network.auth.Auth
                    import com.difft.android.network.auth.AuthStyle
                    import retrofit2.http.GET
                    import retrofit2.http.HTTP

                    @Auth(AuthStyle.JWT)
                    interface UniformService {
                        @GET("v1/a")
                        suspend fun a(): String

                        @HTTP(method = "DELETE", path = "v1/b", hasBody = true)
                        suspend fun b(): String
                    }
                    """,
                ).indented(),
            )
            .issues(AuthStyleDetector.AUTH_STYLE_MISSING)
            .allowMissingSdk()
            .run()
            .expectClean()
    }

    /** T42 — a hand-passed header is forbidden unless the endpoint is EXPLICIT. */
    @Test
    fun `flags a hand-passed Authorization header except on EXPLICIT`() {
        lint()
            .files(
                AUTH_STUB,
                RETROFIT_STUB,
                kotlin(
                    "src/com/difft/android/network/HeaderParamService.kt",
                    """
                    package com.difft.android.network

                    import com.difft.android.network.auth.Auth
                    import com.difft.android.network.auth.AuthStyle
                    import retrofit2.http.Header
                    import retrofit2.http.PUT

                    interface HeaderParamService {
                        @Auth(AuthStyle.BASIC)
                        @PUT("v1/basic")
                        suspend fun basic(@Header("Authorization") baseAuth: String): String

                        @Auth(AuthStyle.EXPLICIT)
                        @PUT("v1/explicit")
                        suspend fun explicit(@Header("Authorization") baseAuth: String): String

                        @Auth(AuthStyle.JWT)
                        @PUT("v1/other")
                        suspend fun otherHeader(@Header("X-Trace") trace: String): String
                    }
                    """,
                ).indented(),
            )
            .issues(AuthStyleDetector.AUTH_HEADER_PARAM_FORBIDDEN)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
            .expectContains("drop the baseAuth parameter")
    }

    /** T42 — an interface-level style also forbids the parameter. */
    @Test
    fun `flags a hand-passed header under an interface-level declaration`() {
        lint()
            .files(
                AUTH_STUB,
                RETROFIT_STUB,
                kotlin(
                    "src/com/difft/android/network/UniformHeaderService.kt",
                    """
                    package com.difft.android.network

                    import com.difft.android.network.auth.Auth
                    import com.difft.android.network.auth.AuthStyle
                    import retrofit2.http.GET
                    import retrofit2.http.Header

                    @Auth(AuthStyle.JWT)
                    interface UniformHeaderService {
                        @GET("v1/a")
                        suspend fun a(@Header("authorization") token: String): String
                    }
                    """,
                ).indented(),
            )
            .issues(AuthStyleDetector.AUTH_HEADER_PARAM_FORBIDDEN)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
    }

    /** T43 — EXPLICIT with the parameter dropped goes out unauthenticated; the real `signin` is clean. */
    @Test
    fun `flags an EXPLICIT endpoint with no Authorization parameter`() {
        lint()
            .files(
                AUTH_STUB,
                RETROFIT_STUB,
                kotlin(
                    "src/com/difft/android/login/service/ExplicitService.kt",
                    """
                    package com.difft.android.login.service

                    import com.difft.android.network.auth.Auth
                    import com.difft.android.network.auth.AuthStyle
                    import retrofit2.http.Body
                    import retrofit2.http.Header
                    import retrofit2.http.PUT
                    import retrofit2.http.Path

                    interface ExplicitService {
                        @Auth(AuthStyle.EXPLICIT)
                        @PUT("v1/accounts/code/{vcode}")
                        suspend fun signinWithoutHeader(
                            @Path("vcode") vcode: String,
                            @Body body: String
                        ): String

                        @Auth(AuthStyle.EXPLICIT)
                        @PUT("v1/accounts/code/{vcode}")
                        suspend fun signin(
                            @Path("vcode") vcode: String,
                            @Header("Authorization") baseAuth: String,
                            @Body body: String
                        ): String
                    }
                    """,
                ).indented(),
            )
            .issues(AuthStyleDetector.AUTH_EXPLICIT_MISSING_HEADER)
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
            .expectContains("signinWithoutHeader is @Auth(AuthStyle.EXPLICIT) but has no")
    }

    private companion object {

        val AUTH_STUB: TestFile = kotlin(
            "src/com/difft/android/network/auth/Auth.kt",
            """
            package com.difft.android.network.auth

            enum class AuthStyle { BASIC, JWT, NONE, EXPLICIT }

            @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
            @Retention(AnnotationRetention.RUNTIME)
            annotation class Auth(val style: AuthStyle)
            """,
        ).indented()

        val RETROFIT_STUB: TestFile = kotlin(
            "src/retrofit2/http/Annotations.kt",
            """
            package retrofit2.http

            annotation class GET(val value: String = "")
            annotation class POST(val value: String = "")
            annotation class PUT(val value: String = "")
            annotation class DELETE(val value: String = "")
            annotation class PATCH(val value: String = "")
            annotation class HEAD(val value: String = "")
            annotation class OPTIONS(val value: String = "")
            annotation class HTTP(val method: String, val path: String = "", val hasBody: Boolean = false)
            annotation class Header(val value: String)
            annotation class Body
            annotation class Path(val value: String)
            """,
        ).indented()
    }
}
