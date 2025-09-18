package com.difft.android.chat.recent

import com.difft.android.chat.message.ChatMessage
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject

object RecentChatUtil {
    private val mConfidentialRecipientSubject = PublishSubject.create<ChatMessage>()
    fun emitConfidentialRecipient(message: ChatMessage) = mConfidentialRecipientSubject.onNext(message)

    val confidentialRecipient: Observable<ChatMessage> = mConfidentialRecipientSubject


    private val mChatDoubleTabSubject = PublishSubject.create<Unit>()
    fun emitChatDoubleTab() = mChatDoubleTabSubject.onNext(Unit)

    val chatDoubleTab: Observable<Unit> = mChatDoubleTabSubject
}