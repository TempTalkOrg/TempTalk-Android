//package com.difft.android.chat.contacts.contactsdetail
//
//import android.content.Context
//import androidx.lifecycle.LifecycleOwner
//import androidx.lifecycle.LiveData
//import androidx.lifecycle.MutableLiveData
//import androidx.lifecycle.ViewModel
//import com.difft.android.base.utils.RxUtil
//import com.difft.android.chat.common.AvatarUtil
//import com.difft.android.chat.contacts.data.ContactorUtil
//import com.difft.android.messageserialization.db.store.DBContactsStore
//import com.difft.android.messageserialization.db.store.model.Contactor
//import com.difft.android.network.BaseResponse
//import com.difft.android.network.NetworkException
//import com.difft.android.network.responses.AddContactorResponse
//import com.difft.android.network.viewmodel.Resource
//import io.reactivex.rxjava3.core.Observable
//
//class ContactDetailViewModel : ViewModel() {
//
//    private val mContactorResultData = MutableLiveData<Resource<Contactor>>()
//    internal val contactorResultData: LiveData<Resource<Contactor>> = mContactorResultData
//
//    private val mAddContactorResultData = MutableLiveData<Resource<BaseResponse<AddContactorResponse>>>()
//    internal val addContactorResultData: LiveData<Resource<BaseResponse<AddContactorResponse>>> = mAddContactorResultData
//
//    private val mRemoveContactorResultData = MutableLiveData<Resource<BaseResponse<Any>>>()
//    internal val removeContactorResultData: LiveData<Resource<BaseResponse<Any>>> = mRemoveContactorResultData
//
//    private val mAvatarData = MutableLiveData<ByteArray>()
//    internal val avatarData: LiveData<ByteArray> = mAvatarData
//
//    fun getContactorInfo(context: Context, contactorID: String) {
//        mContactorResultData.value = Resource.loading()
//        ContactorUtil.fetchContactors(listOf(contactorID), context)
//            .compose(RxUtil.getSingleSchedulerComposer())
//            .to(RxUtil.autoDispose(context as LifecycleOwner))
//            .subscribe({
//                if (it.isNotEmpty()) {
//                    mContactorResultData.value = Resource.success(it.first())
//                } else {
//                    mContactorResultData.value = Resource.error(NetworkException(message = ""))
//                }
//            }) {
//                mContactorResultData.value = Resource.error(NetworkException(message = it.message ?: ""))
//            }
//    }
//
//    fun addContact(context: Context, token: String, contactorID: String) {
//        mContactorResultData.value = Resource.loading()
//        ContactorUtil.fetchAddFriendRequest(context, token, contactorID)
//            .compose(RxUtil.getSingleSchedulerComposer())
//            .to(RxUtil.autoDispose(context as LifecycleOwner))
//            .subscribe({
//                mAddContactorResultData.value = Resource.success(it)
//            }) {
//                mAddContactorResultData.value = Resource.error(NetworkException(message = it.message ?: ""))
//            }
//    }
//
//    fun getAvatar(context: Context, url: String, key: String) {
//        AvatarUtil.fetchAvatar(context, url, key)
//            .compose(RxUtil.getSchedulerComposer())
//            .to(RxUtil.autoDispose(context as LifecycleOwner))
//            .subscribe({
//                mAvatarData.value = it
//            }) {
//                it.printStackTrace()
//            }
//    }
//
//    fun removeContact(context: Context, token: String, contactId: String) {
//        ContactorUtil.fetchRemoveFriend(context, token, contactId)
//            .toObservable()
//            .concatMap {
//                if (it.status == 0) {
//                    DBContactsStore.removeContactWithID(contactId).blockingAwait()
//                    Observable.just(it)
//                } else {
//                    Observable.empty()
//                }
//            }
//            .compose(RxUtil.getSchedulerComposer())
//            .to(RxUtil.autoDispose(context as LifecycleOwner))
//            .subscribe({
//                ContactorUtil.emitContactsUpdate(listOf(contactId))
//                mRemoveContactorResultData.value = Resource.success(it)
//            }) {
//                mAddContactorResultData.value = Resource.error(NetworkException(message = it.message ?: ""))
//                it.printStackTrace()
//            }
//    }
//
//
//}