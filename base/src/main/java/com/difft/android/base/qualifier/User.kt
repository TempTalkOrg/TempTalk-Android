package com.difft.android.base.qualifier

import javax.inject.Qualifier


class User {
    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class Account

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class Password

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class Token


    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class RegistrationId

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class Uid

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class IdentityKey
}
