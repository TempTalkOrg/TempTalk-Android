package com.difft.android.network.auth


enum class AuthStyle {
    
    BASIC,

    
    JWT,

    
    NONE,

    




    EXPLICIT,
}









@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Auth(val style: AuthStyle)
