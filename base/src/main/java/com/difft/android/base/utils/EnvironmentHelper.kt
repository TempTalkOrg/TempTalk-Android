package com.difft.android.base.utils

interface EnvironmentHelper {
    val currentEnvironment: String

    val ENVIRONMENT_DEVELOPMENT: String
    val ENVIRONMENT_ONLINE: String

    fun isThatEnvironment(environment: String): Boolean

    fun isGoogleChannel(): Boolean

    fun isInsiderChannel(): Boolean

    fun getChannelName(): String

    fun getScheme(): String
}