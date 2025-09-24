package com.difft.android.call


import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CallModule {
    @Binds
    @Singleton
    abstract fun bindChatToCallController(lChatToCallControllerImpl: LChatToCallControllerImpl): LChatToCallController

    @Binds
    @Singleton
    abstract fun bindCallToChatController(lCallToChatControllerImpl: LCallToChatControllerImpl): LCallToChatController
}
