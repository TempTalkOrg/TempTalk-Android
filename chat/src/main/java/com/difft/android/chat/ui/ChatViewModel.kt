//package com.difft.android.chat.signal
//
//import androidx.lifecycle.ViewModel
//
//import com.difft.android.base.utils.ApplicationHelper
//import com.difft.android.base.utils.RxUtil
//import com.difft.android.chat.contacts.data.ContactorUtil
//import com.difft.android.chat.group.ChatUIData
//import com.difft.android.messageserialization.db.store.model.Contactor
//import io.reactivex.rxjava3.disposables.CompositeDisposable
//import io.reactivex.rxjava3.subjects.BehaviorSubject
//import io.reactivex.rxjava3.subjects.Subject
//
//class ChatViewModel : ViewModel() {
//    private val disposableManager = CompositeDisposable()
//
//    var chatUIData: Subject<ChatUIData> = BehaviorSubject.create()
//    var mySelf: Subject<Contactor> = BehaviorSubject.create()
//
//    init {
//        disposableManager.add(
//            LoginHelper.requestMyID(application)
//                .concatMap { id -> ContactorUtil.getContactWithID(application, id) }
//                .compose(RxUtil.getSingleSchedulerComposer())
//                .subscribe({
//                    if (it.isPresent) {
//                        mySelf.onNext(it.get())
//                    }
//                }) {
//                    mySelf.onNext(Contactor.Empty)
//                }
//        )
//    }
//
//    fun setChatUIData(data: ChatUIData){
//        chatUIData.onNext(data)
//    }
//
//    override fun onCleared() {
//        disposableManager.dispose()
//
//        super.onCleared()
//    }
//}