package com.difft.android.network.auth

import okhttp3.Request
import retrofit2.Invocation










object AuthStyleResolver {

    
    fun styleFor(request: Request): AuthStyle? {
        val invocation = request.tag(Invocation::class.java) ?: return null
        return invocation.method().getAnnotation(Auth::class.java)?.style
            ?: invocation.service().getAnnotation(Auth::class.java)?.style
    }

    
    fun describe(request: Request): String {
        val invocation = request.tag(Invocation::class.java) ?: return "unknown(no-invocation-tag)"
        return "${invocation.service().simpleName}.${invocation.method().name}"
    }
}
