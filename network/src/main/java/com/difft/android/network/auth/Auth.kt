package com.difft.android.network.auth

/** How an endpoint's `Authorization` header is produced. Declared once, per endpoint, via [Auth]. */
enum class AuthStyle {
    /** The interceptor supplies the persisted Basic credential (`UserData.baseAuth`). */
    BASIC,

    /** The interceptor supplies a fresh account JWT from [AuthTokenProvider.current]. */
    JWT,

    /** The interceptor removes `Authorization`. Used where the credential travels elsewhere. */
    NONE,

    /**
     * The endpoint declares its own `Authorization` parameter and the interceptor must not touch
     * it. Required for the routes that run before `baseAuth` is persisted (login sign-in, pre-key
     * registration, the token refresh itself).
     */
    EXPLICIT,
}

/**
 * The auth style of a Retrofit endpoint, read at interceptor time from Retrofit's `Invocation`
 * request tag. May sit on the service interface (uniform services) or on a single method (mixed
 * services); a method declaration wins over its interface (issue #1231).
 *
 * Retention MUST stay RUNTIME — the resolver reads it reflectively, and a SOURCE retention would
 * silently leave every endpoint undeclared.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Auth(val style: AuthStyle)
