package com.difft.android.di

import android.content.Context
import com.difft.android.app.TempTalkApplication
import com.difft.android.base.user.LogoutManager
import com.difft.android.setting.AppExiter
import com.difft.android.setting.AppExiterImpl
import com.difft.android.setting.AppVisibility
import com.difft.android.setting.LogoutManagerImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
interface ApplicationModule {
    @Binds
    @Singleton
    fun bindLogoutManager(logoutManagerImpl: LogoutManagerImpl): LogoutManager

    @Binds
    @Singleton
    fun bindAppExiter(appExiterImpl: AppExiterImpl): AppExiter

    companion object {
        /**
         * [TempTalkApplication] is the only component that tracks a resumed FragmentActivity, so
         * the visibility seam reads through it rather than duplicating the tracking.
         */
        @Provides
        @Singleton
        fun provideAppVisibility(@ApplicationContext context: Context): AppVisibility =
            AppVisibility { (context as? TempTalkApplication)?.hasResumedActivity() == true }
    }
}
