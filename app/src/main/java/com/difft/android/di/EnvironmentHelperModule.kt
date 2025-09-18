package com.difft.android.di


import com.difft.android.BuildConfig
import com.difft.android.base.utils.AppScheme
import com.difft.android.base.utils.EnvironmentHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
class EnvironmentHelperModule {
    @Provides
    fun provideHelper(): EnvironmentHelper {
        return object : EnvironmentHelper {
            override val currentEnvironment: String = BuildConfig.ENVIRONMENT
            override val ENVIRONMENT_DEVELOPMENT: String = BuildConfig.ENVIRONMENT_DEVELOPMENT
            override val ENVIRONMENT_ONLINE: String = BuildConfig.ENVIRONMENT_ONLINE

            override fun isThatEnvironment(environment: String): Boolean {
                return currentEnvironment == environment
            }

            override fun isGoogleChannel(): Boolean {
                return BuildConfig.APP_CHANNEL.contains("google")
            }

            override fun isInsiderChannel(): Boolean {
                return BuildConfig.APP_CHANNEL.contains("insider")
            }

            override fun getChannelName(): String {
                return BuildConfig.APP_CHANNEL
            }

            override fun getScheme(): String {
                return if (isThatEnvironment(ENVIRONMENT_DEVELOPMENT)) AppScheme.SCHEME_CHATIVE_TEST else AppScheme.SCHEME_CHATIVE
            }
        }
    }
}