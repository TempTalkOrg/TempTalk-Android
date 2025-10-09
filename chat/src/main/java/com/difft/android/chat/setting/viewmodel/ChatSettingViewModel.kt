package com.difft.android.chat.setting.viewmodel

import android.app.Activity
import android.text.TextUtils
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import autodispose2.autoDispose
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.chat.R
import com.difft.android.chat.contacts.data.ContactorUtil.getEntryPoint
import com.difft.android.chat.setting.archive.MessageArchiveManager
import difft.android.messageserialization.For
import com.difft.android.network.requests.ConversationSetRequestBody
import com.difft.android.network.requests.GetConversationSetRequestBody
import com.difft.android.network.responses.ConversationSetResponseBody
import com.difft.android.base.widget.ComposeDialogManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.CompletableSubject
import java.util.concurrent.TimeUnit
import com.difft.android.base.widget.ToastUtil
@HiltViewModel(assistedFactory = ChatSettingViewModelFactory::class)
class ChatSettingViewModel @AssistedInject constructor(
    @Assisted
    val conversation: For,
    private val messageArchiveManager: MessageArchiveManager,
) : ViewModel() {
    private val autoDisposeCompletable = CompletableSubject.create()

    private val _conversationSet = BehaviorSubject.create<ConversationSetResponseBody>()
    val conversationSet: Observable<ConversationSetResponseBody> = _conversationSet
    
    private fun updateConversationSetResponseBody(conversationSetResponseBody: ConversationSetResponseBody) {
        _conversationSet.onNext(conversationSetResponseBody)
    }

    fun updateSelectedOption(activity: Activity, time: Long) {
        ComposeDialogManager.showWait(activity, "")
        messageArchiveManager.updateMessageArchiveTime(conversation, time)
            .compose(RxUtil.getCompletableTransformer())
            .autoDispose(autoDisposeCompletable)
            .subscribe({
                ComposeDialogManager.dismissWait()
            }, {
                ComposeDialogManager.dismissWait()
                it.printStackTrace()
                it.message?.let { message -> ToastUtil.show(message) }
            })
    }


    fun setConversationConfigs(
        activity: Activity,
        conversation: String,
        remark: String? = null,
        muteStatus: Int? = null,
        blockStatus: Int? = null,
        confidentialMode: Int? = null,
        needFinishActivity: Boolean = false,
        successTips: String? = null
    ) {
        activity
            .getEntryPoint()
            .getHttpClient()
            .httpService
            .fetchConversationSet(
                SecureSharedPrefsUtil.getBasicAuth(),
                ConversationSetRequestBody(
                    conversation,
                    remark,
                    muteStatus,
                    blockStatus,
                    confidentialMode
                )
            )
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(activity as LifecycleOwner))
            .subscribe({
                if (it.status == 0) {
                    it.data?.let { conversationSet ->
                        updateConversationSetResponseBody(conversationSet)
                    }
                    if (!TextUtils.isEmpty(successTips)) {
                        successTips?.let { message -> ToastUtil.show(message) }
                        if (needFinishActivity) {
                            Observable.just(Unit)
                                .delay(2000, TimeUnit.MILLISECONDS)
                                .compose(RxUtil.getSchedulerComposer())
                                .to(RxUtil.autoDispose(activity))
                                .subscribe({
                                    activity.finish()
                                }, {})
                        }
                    } else {
                        if (needFinishActivity) {
                            activity.finish()
                        }
                    }
                } else {
                    it.reason?.let { message -> ToastUtil.show(message) }
                }
            }) {
                it.printStackTrace()
                L.w { "[ChatSettings] setConversationConfigs error:" + it.stackTraceToString() }
                ToastUtil.show(activity.getString(R.string.operation_failed))
            }
    }

    fun getConversationConfigs(
        activity: Activity,
        conversations: List<String>
    ) {

        activity
            .getEntryPoint()
            .getHttpClient()
            .httpService.fetchGetConversationSet(
                SecureSharedPrefsUtil.getBasicAuth(),
                GetConversationSetRequestBody(conversations)
            )
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(activity as LifecycleOwner))
            .subscribe({
                if (it.status == 0) {
                    it.data?.let { conversationSets ->
                        val conversationSet = conversationSets.conversations.find { body -> body.conversation == conversations[0] }
                        conversationSet?.let { set ->
                            updateConversationSetResponseBody(set)
                        }
                    }
                } else {
                    it.reason?.let { message -> ToastUtil.show(message) }
                }
            }) {
//                ToastUtil.show(it.message)
            }
    }

//    fun reportContact(activity: Activity, contactID: String) {
//        activity
//            .getEntryPoint()
//            .getHttpClient()
//            .httpService.reportContact(
//                SecureSharedPrefsUtil.getToken(),
//                ReportContactRequestBody(contactID, null, null, 1)
//            )
//            .compose(RxUtil.getSingleSchedulerComposer())
//            .to(RxUtil.autoDispose(activity as LifecycleOwner))
//            .subscribe({
//                if (it.status == 0) {
////                    ContactorUtil.createFriendRequestSentMessage(contactID)
//                } else {
//                    ToastUtil.show(it.reason)
//                }
//            }) {
//                ToastUtil.show(it.message)
//            }
//    }

    override fun onCleared() {
        autoDisposeCompletable.onComplete()

        super.onCleared()
    }
}