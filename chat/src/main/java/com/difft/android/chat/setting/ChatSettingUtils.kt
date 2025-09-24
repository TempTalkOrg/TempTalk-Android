package com.difft.android.chat.setting

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject

object ChatSettingUtils {

    private val mConversationSettingSubject = BehaviorSubject.create<String>()

    fun emitConversationSettingUpdate(conversationId: String) = mConversationSettingSubject.onNext(conversationId)

    val conversationSettingUpdate: Observable<String> = mConversationSettingSubject
}

