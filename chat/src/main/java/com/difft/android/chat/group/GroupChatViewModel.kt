//package com.difft.android.chat.group
//
//import androidx.lifecycle.ViewModel
//
//import com.difft.android.base.qualifier.User
//import com.difft.android.base.utils.ApplicationHelper
//import com.difft.android.base.utils.RxUtil
//import com.difft.android.chat.contacts.data.ContactorUtil
//import com.difft.android.messageserialization.db.store.model.Contactor
//import com.difft.android.messageserialization.db.store.model.Group
//import com.difft.android.network.HttpService
//import dagger.hilt.android.lifecycle.HiltViewModel
//import io.reactivex.rxjava3.core.Observable
//import io.reactivex.rxjava3.core.Single
//import io.reactivex.rxjava3.disposables.CompositeDisposable
//import io.reactivex.rxjava3.subjects.BehaviorSubject
//import io.reactivex.rxjava3.subjects.Subject
//import javax.inject.Inject
//
//@HiltViewModel
//class GroupChatViewModel @Inject constructor(
//    val service: HttpService,
//    @User.Token
//    val baseAuth: Single<String>
//) : ViewModel() {
//    private val disposableManager = CompositeDisposable()
//
//    var mySelf: Subject<Contactor> = BehaviorSubject.create()
//
////    private var _gid = BehaviorSubject.create<String>()
////    val gid: Observable<String> = _gid
//
////    private var _groupBundle = BehaviorSubject.create<Pair<Int, GetGroupInfoResp?>>()
////    val groupBundle: Observable<Pair<Int, GetGroupInfoResp?>> = _groupBundle
////
////    private var _groupMembers = BehaviorSubject.create<List<Contactor>>()
////    val groupMembers: Observable<List<Contactor>> = _groupMembers
//
//    private var _groupInfo = BehaviorSubject.create<Group>()
//    val groupInfo: Observable<Group> = _groupInfo
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
////    fun setGid(gid: String) {
////        _gid.onNext(gid)
////    }
//
//    fun setGroupInfo(group: Group) {
//        _groupInfo.onNext(group)
//    }
//
////    fun setGroupBundle(status: Int, groupBundle: GetGroupInfoResp?) {
////        _groupBundle.onNext(status to groupBundle)
////    }
////
////    fun setGroupMembers(members: List<Contactor>) {
////        _groupMembers.onNext(members)
////    }
//
////    private var _myself = BehaviorSubject.create<Optional<Contactor>>()
////    val myself: Observable<Optional<Contactor>> = _myself
////    fun setMyself(myself: Contactor?) {
////        _myself.onNext(Optional.ofNullable(myself))
////    }
//}