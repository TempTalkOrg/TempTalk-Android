package com.difft.android.chat.ui

import android.view.ViewGroup
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.message.ChatMessage




interface ContentBinder {
    








    fun bind(contentFrame: ViewGroup, message: ChatMessage, contactorCache: MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int)

    




    fun rebindForWidth(contentFrame: ViewGroup, message: ChatMessage, contactorCache: MessageContactsCacheUtil, shouldSaveToPhotos: Boolean, containerWidth: Int) = Unit
}
