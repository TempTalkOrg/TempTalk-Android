package com.difft.android.chat.attachment.videoprobe

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class VideoProbeModule {
    @Binds
    abstract fun bindVideoProbeStore(store: WcdbVideoProbeStore): VideoProbeStore

    @Binds
    abstract fun bindVideoFrameProber(prober: MediaRetrieverVideoFrameProber): VideoFrameProber
}
