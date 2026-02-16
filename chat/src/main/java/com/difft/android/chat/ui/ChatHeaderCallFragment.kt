package com.difft.android.chat.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.application
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallManager
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.util.IdUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.header.CommonHeaderFragment
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.databinding.ChatFragmentHeaderCallBinding
import com.difft.android.chat.databinding.ChatFragmentHeaderCallStubBinding
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.group.getAvatarData
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.rx3.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import javax.inject.Inject


@AndroidEntryPoint
class ChatHeaderCallFragment : CommonHeaderFragment() {

    @Inject
    @ChativeHttpClientModule.Call
    lateinit var callHttpClient: ChativeHttpClient

    @Inject
    lateinit var onGoingCallStateManager: OnGoingCallStateManager

    @Inject
    lateinit var callDataManager: CallDataManager

    private val callService by lazy {
        callHttpClient.getService(LCallHttpService::class.java)
    }

    private lateinit var stubBinding: ChatFragmentHeaderCallStubBinding
    private var callBinding: ChatFragmentHeaderCallBinding? = null


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        callBinding?.callBarView?.visibility = View.GONE

        registerCallingListener()
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val binding = ChatFragmentHeaderCallStubBinding.inflate(inflater, container, false)
        stubBinding = binding
        return binding.root
    }

    override fun onDestroyView() {
        callBinding = null
        super.onDestroyView()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun registerCallingListener() {
        combine(
            callDataManager.callingList,
            onGoingCallStateManager.isInCalling
        ) { callingList, isInCalling ->
            callingList to isInCalling
        }
            // 计算当前需要处理的 callData
            .map { (callingList, isInCalling) ->
                if (!isInCalling) {
                    callingList.values.firstOrNull()
                } else {
                    callingList.values.firstOrNull {
                        onGoingCallStateManager.getCurrentRoomId() != it.roomId
                    }
                }
            }
            // 去重，避免重复请求
            .distinctUntilChanged { old, new ->
                old?.roomId == new?.roomId
            }
            // 根据 callData 发起异步请求（自动取消旧请求）
            .flatMapLatest { callData ->
                if (callData == null || callData.roomId.isEmpty()) {
                    flowOf(null)
                } else {
                    flow {
                        val result = callService.checkCall(
                            SecureSharedPrefsUtil.getToken(),
                            callData.roomId
                        ).await()
                        emit(callData to result)
                    }
                        .flowOn(Dispatchers.IO)
                }
            }
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            // UI 层处理结果
            .onEach { result ->
                if (result == null) {
                    hideCallBarViews()
                    return@onEach
                }

                val (callData, checkCallResult) = result

                if (checkCallResult.status != 0) {
                    hideCallBarViews()
                    return@onEach
                }

                val anotherDeviceJoined =
                    checkCallResult.data?.anotherDeviceJoined ?: false
                val callType = callData.type

                val displayInfo = withContext(Dispatchers.IO) {
                    loadCallDisplayInfo(callType, callData, anotherDeviceJoined)
                }

                if (!isAdded || view == null) return@onEach
                updateCallBarView(
                    callType,
                    callData,
                    displayInfo,
                    anotherDeviceJoined
                )
            }
            .catch { e ->
                L.e { "[Call] ChatHeaderCallFragment error = ${e.message}" }
                hideCallBarViews()
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    /**
     * 通话显示信息数据类
     */
    private data class CallDisplayInfo(
        val callerName: String,
        val contact: java.util.Optional<org.difft.app.database.models.ContactorModel>?,
        val groupName: String?,
        val groupInfo: java.util.Optional<org.difft.app.database.models.GroupModel>?
    )

    /**
     * 在 IO 线程加载通话显示信息（群组名称、联系人信息等）
     */
    private suspend fun loadCallDisplayInfo(
        callType: String?,
        callData: com.difft.android.base.call.CallData,
        anotherDeviceJoined: Boolean
    ): CallDisplayInfo {
        var callerName = ""
        var contact: java.util.Optional<org.difft.app.database.models.ContactorModel>? = null
        var groupName: String? = null
        var groupInfo: java.util.Optional<org.difft.app.database.models.GroupModel>? = null

        when {
            callType == CallType.GROUP.type && callData.conversation != null -> {
                groupInfo = GroupUtil.getSingleGroupInfo(application, callData.conversation!!)
                    .asFlow()
                    .firstOrNull()
                groupName = groupInfo?.takeIf { it.isPresent }?.get()?.name
            }
            callType == CallType.ONE_ON_ONE.type -> {
                val uid = if(anotherDeviceJoined) globalServices.myId else callData.caller.uid
                uid?.let {
                    contact = ContactorUtil.getContactWithID(application, uid).await()
                    callerName = if (contact?.isPresent == true) {
                        contact!!.get().getDisplayNameForUI()
                    } else {
                        IdUtil.convertToBase58UserName(uid) ?: uid
                    }
                }
            }
        }

        return CallDisplayInfo(callerName, contact, groupName, groupInfo)
    }

    /**
     * 在主线程更新通话栏视图
     */
    private fun updateCallBarView(
        callType: String?,
        callData: com.difft.android.base.call.CallData,
        displayInfo: CallDisplayInfo,
        anotherDeviceJoined: Boolean
    ) {
        val binding = ensureCallBinding()
        when {
            callType == CallType.GROUP.type && callData.conversation != null -> {
                if (displayInfo.groupInfo?.isPresent == true) {
                    binding.imageviewGroupAvatar.visibility = View.VISIBLE
                    binding.imageviewGroupAvatar.setAvatar(displayInfo.groupInfo.get().avatar?.getAvatarData())
                } else {
                    binding.imageviewGroupAvatar.visibility = View.VISIBLE
                    binding.imageviewAvatar.setAvatar(com.difft.android.base.R.drawable.base_ic_group)
                }
                binding.callStatusText.text = displayInfo.groupName 
                    ?: getString(com.difft.android.call.R.string.call_chat_header_started_call, displayInfo.callerName)
            }
            callType == CallType.ONE_ON_ONE.type -> {
                val uid = if(anotherDeviceJoined) globalServices.myId else callData.caller.uid
                uid?.let {
                    if (displayInfo.contact?.isPresent == true) {
                        binding.imageviewAvatar.visibility = View.VISIBLE
                        binding.imageviewAvatar.setAvatar(displayInfo.contact!!.get())
                    } else {
                        binding.imageviewAvatar.setAvatar(com.difft.android.base.R.drawable.base_ic_avatar_default)
                    }
                    binding.callStatusText.text = if(anotherDeviceJoined) {
                        ResUtils.getString(com.difft.android.call.R.string.call_chat_header_1v1_another_device)
                    } else {
                        getString(com.difft.android.call.R.string.call_chat_header_1v1_calling, displayInfo.callerName)
                    }
                }
            }
            else -> {
                binding.imageviewAvatar.visibility = View.VISIBLE
                binding.imageviewAvatar.setAvatar(com.difft.android.base.R.drawable.base_ic_instant_call)
                binding.callStatusText.text = callData.callName
            }
        }

        binding.buttonJoinCall.text = ResUtils.getString(R.string.call_join)
        binding.buttonJoinCall.setOnClickListener {
            L.i { "[Call] ChatHeaderCallFragment onclick button joinCall roomId:${callData.roomId}" }
            LCallManager.joinCall(requireActivity(), callData) { status ->
                if(!status) {
                    L.e { "[Call] ChatHeaderCallFragment join call failed." }
                    ToastUtil.show(com.difft.android.call.R.string.call_join_failed_tip)
                }
            }
        }
        binding.buttonJoinCall.visibility = View.VISIBLE
        binding.callBarView.visibility = View.VISIBLE

        // set call header visible to true
        onGoingCallStateManager.setChatHeaderCallVisibility(true)
    }

    private fun hideCallBarViews() {
        callBinding?.callBarView?.visibility = View.GONE
        onGoingCallStateManager.setChatHeaderCallVisibility(false)
    }

    private fun ensureCallBinding(): ChatFragmentHeaderCallBinding {
        callBinding?.let { return it }
        val inflated = stubBinding.callHeaderStub.inflate()
        val binding = ChatFragmentHeaderCallBinding.bind(inflated)
        callBinding = binding
        return binding
    }


}