package com.difft.android.chat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.application
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallManager
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.chat.R
import com.difft.android.chat.common.header.CommonHeaderFragment
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.databinding.ChatFragmentHeaderCallBinding
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.group.getAvatarData
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.launch
import javax.inject.Inject


@AndroidEntryPoint
class ChatHeaderCallFragment : CommonHeaderFragment() {

    @Inject
    @ChativeHttpClientModule.Call
    lateinit var callHttpClient: ChativeHttpClient

    private val callService by lazy {
        callHttpClient.getService(LCallHttpService::class.java)
    }

    private lateinit var binding: ChatFragmentHeaderCallBinding


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.callBarView.visibility = View.GONE

        registerCallingListener()
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val binding = ChatFragmentHeaderCallBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }


    private fun registerCallingListener() {
        LCallManager.callingList
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe(
                { callingList ->
                    L.d { "[Call] ChatHeaderCallFragment listener callingList = $callingList" }
                    val callData = if(!LCallActivity.isInCalling()) callingList.values.firstOrNull() else callingList.values.firstOrNull { LCallActivity.getCurrentRoomId() != it.roomId }
                    if(callData != null && callData.roomId.isNotEmpty()) {
                        callService.checkCall(SecureSharedPrefsUtil.getToken(), callData.roomId)
                            .subscribeOn(Schedulers.io())
                            .to(RxUtil.autoDispose(this))
                            .subscribe({ it ->
                                L.d { "[Call] ChatHeaderCallFragment  check call result = $it" }
                                if(it.status == 0){
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        val anotherDeviceJoined = it.data?.anotherDeviceJoined ?: false
                                        val callType = callData.type
                                        var callerName = ""

                                        if(callType == CallType.GROUP.type && callData.conversation != null) {
                                            val groupInfo = GroupUtil.getSingleGroupInfo(application, callData.conversation!!).blockingFirst()
                                            val groupName = if(groupInfo.isPresent) {
                                                binding.imageviewGroupAvatar.visibility = View.VISIBLE
                                                binding.imageviewGroupAvatar.setAvatar(groupInfo.get().avatar?.getAvatarData())
                                                groupInfo.get().name
                                            }else{
                                                binding.imageviewGroupAvatar.visibility = View.VISIBLE
                                                binding.imageviewAvatar.setAvatar(com.difft.android.base.R.drawable.base_ic_group)
                                                null
                                            }
                                            binding.callStatusText.text = groupName ?: getString(com.difft.android.call.R.string.call_chat_header_started_call, callerName)
                                        } else if (callType == CallType.ONE_ON_ONE.type) {
                                            val uid = if(anotherDeviceJoined) globalServices.myId else callData.caller.uid
                                            uid?.let {
                                                val contact = ContactorUtil.getContactWithID(
                                                    application, uid
                                                ).blockingGet()
                                                if (contact.isPresent){
                                                    binding.imageviewAvatar.visibility = View.VISIBLE
                                                    binding.imageviewAvatar.setAvatar(contact.get())
                                                    callerName = contact.get().getDisplayNameForUI()
                                                }else{
                                                    binding.imageviewAvatar.setAvatar(com.difft.android.base.R.drawable.base_ic_avatar_default)
                                                    callerName = LCallManager.convertToBase58UserName(uid) ?: uid
                                                }
                                                binding.callStatusText.text = if(anotherDeviceJoined) {
                                                    ResUtils.getString(com.difft.android.call.R.string.call_chat_header_1v1_another_device)
                                                } else {
                                                    getString(com.difft.android.call.R.string.call_chat_header_1v1_calling, callerName)
                                                }
                                            }
                                        } else {
                                            binding.imageviewAvatar.visibility = View.VISIBLE
                                            binding.imageviewAvatar.setAvatar(com.difft.android.base.R.drawable.base_ic_instant_call)
                                            binding.callStatusText.text = callData.callName
                                        }

                                        binding.buttonJoinCall.text = ResUtils.getString(R.string.call_join)
                                        binding.buttonJoinCall.setOnClickListener {
                                            L.i { "[Call] ChatHeaderCallFragment onclick button joinCall roomId:${callData.roomId}" }
                                            LCallManager.joinCall(requireActivity(), callData.roomId)
                                        }
                                        binding.buttonJoinCall.visibility = View.VISIBLE
                                        binding.callBarView.visibility = View.VISIBLE

                                        // set call header visible to true
                                        LCallManager.setChatHeaderCallVisibility(true)
                                    }
                                }else{
                                    hideCallBarViews()
                                }
                            },{ it ->
                                L.e { "[Call] ChatHeaderCallFragment check call error = ${it.message}" }
                                it.printStackTrace()
                                hideCallBarViews()
                            })
                    } else {
                        hideCallBarViews()
                    }
                },
                {
                    L.e { "[Call] ChatHeaderCallFragment registerCallingListener error:${it.message}" }
                    it.printStackTrace()
                    hideCallBarViews()
                }
            )
    }

    private fun hideCallBarViews() {
        binding.callBarView.visibility = View.GONE
        LCallManager.setChatHeaderCallVisibility(false)
    }


}