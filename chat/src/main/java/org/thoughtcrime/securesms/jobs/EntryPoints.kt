package org.thoughtcrime.securesms.jobs

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.thoughtcrime.securesms.messages.NewMessageDecryptionUtil

@InstallIn(SingletonComponent::class)
@EntryPoint
interface EntryPoints {
    fun newMessageDecryptionUtil(): NewMessageDecryptionUtil
}