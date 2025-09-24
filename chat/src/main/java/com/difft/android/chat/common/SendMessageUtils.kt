package com.difft.android.chat.common

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.PublishSubject
import org.difft.app.database.models.ContactorModel
import java.util.concurrent.TimeUnit

data class SendMessageData(
    val contactId: String?,
    val text: String,
    val isPrefill: Boolean,
    val isForSpooky: Boolean = false,
    val mentions: List<ContactorModel>? = null
)

object SendMessageUtils {

    private val currentChatList = LinkedHashSet<String>()
    fun addToCurrentChat(id: String) {
        currentChatList.add(id)
    }

    fun removeFromCurrentChat(id: String) {
        currentChatList.remove(id)
    }

    fun isExistChat(id: String?): Boolean {
        return currentChatList.contains(id)
    }

    fun getTopChat(): String? {
        return currentChatList.toList().lastOrNull()
    }


    private val mSendMessageSubject = PublishSubject.create<SendMessageData>()
    fun emitSendMessage(
        contactId: String?,
        text: String,
        isPrefill: Boolean,
        isForSpooky: Boolean = false,
        mentions: List<ContactorModel>? = null
    ): Disposable =
        Observable.timer(200, TimeUnit.MILLISECONDS)
            .subscribe {
                mSendMessageSubject.onNext(SendMessageData(contactId, text, isPrefill, isForSpooky, mentions))
            }

    val sendMessage: Observable<SendMessageData> = mSendMessageSubject
}

enum class SendType(val rawValue: Int) {
    Sending(0), Sent(1), SentFailed(2)
}
