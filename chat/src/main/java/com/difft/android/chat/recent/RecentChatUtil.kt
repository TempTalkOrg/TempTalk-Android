package com.difft.android.chat.recent

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject

object RecentChatUtil {
    private val mChatDoubleTabSubject = PublishSubject.create<Unit>()
    fun emitChatDoubleTab() = mChatDoubleTabSubject.onNext(Unit)

    val chatDoubleTab: Observable<Unit> = mChatDoubleTabSubject
}