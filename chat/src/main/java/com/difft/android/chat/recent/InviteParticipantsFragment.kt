package com.difft.android.chat.recent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ResUtils
import org.difft.app.database.getContactorsFromAllTable
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallToChatController
import com.difft.android.chat.R
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.getFirstLetter
import com.difft.android.chat.databinding.FragmentInviteParticipantsBinding
import com.difft.android.chat.group.GROUP_ROLE_MEMBER
import com.difft.android.chat.group.GroupMemberModel
import com.difft.android.chat.group.GroupSelectMemberActivity
import com.difft.android.meeting.activities.AttendeeClickListener
import com.difft.android.meeting.activities.AttendeeListItem
import com.difft.android.meeting.activities.UserMeetingAttendeeAdapter
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.thoughtcrime.securesms.util.ViewUtil

@AndroidEntryPoint
class InviteParticipantsFragment : Fragment() {

    companion object {
        fun newInstance(
            actionType: Int,
            channelName: String? = null,
            meetingName: String? = null,
            meetingId: Int? = null,
            shouldBringCallScreenBack: Boolean = false,
            isLiveStream: Boolean?,
            eid: String?,
            excludedIds: ArrayList<String>?,
        ): InviteParticipantsFragment {
            val fragment = InviteParticipantsFragment()
            val args = Bundle()
            args.putInt(
                InviteParticipantsActivity.EXTRA_ACTION_TYPE, actionType
            )
            args.putString(
                InviteParticipantsActivity.EXTRA_CHANNEL_NAME, channelName
            )
            args.putString(
                InviteParticipantsActivity.EXTRA_MEETING_NAME, meetingName
            )
            args.putInt(
                InviteParticipantsActivity.EXTRA_MEETING_ID, meetingId ?: 0
            )
            args.putBoolean(
                InviteParticipantsActivity.EXTRA_SHOULD_BRING_CALL_SCREEN_BACK,
                shouldBringCallScreenBack
            )
            args.putString(
                InviteParticipantsActivity.EXTRA_EID, eid
            )
            args.putBoolean(
                InviteParticipantsActivity.EXTRA_IS_LIVE_STREAM,
                isLiveStream ?: false
            )
            args.putStringArrayList(
                InviteParticipantsActivity.EXTRA_EXCLUDE_IDS,
                ArrayList(excludedIds ?: mutableListOf())
            )
            fragment.arguments = args
            return fragment
        }

        fun newCallInstance(
            callType: String?,
            actionType: Int,
            roomId: String? = null,
            conversationId: String? = null,
            mKey: ByteArray? = null,
            excludedIds: ArrayList<String>?,
            callName: String?
        ): InviteParticipantsFragment {
            val fragment = InviteParticipantsFragment()
            val args = Bundle()
            args.putInt(
                InviteParticipantsActivity.EXTRA_ACTION_TYPE, actionType
            )
            args.putString(
                InviteParticipantsActivity.EXTRA_ROOM_ID, roomId
            )
            args.putString(
                InviteParticipantsActivity.EXTRA_CALL_NAME, callName
            )
            args.putStringArrayList(
                InviteParticipantsActivity.EXTRA_EXCLUDE_IDS,
                ArrayList(excludedIds ?: mutableListOf())
            )
            args.putString(
                InviteParticipantsActivity.EXTRA_CONVERSATION_ID, conversationId
            )
            args.putString(
                InviteParticipantsActivity.EXTRA_CALL_TYPE, callType
            )
            args.putByteArray(
                InviteParticipantsActivity.EXTRA_CALL_MKEY, mKey
            )
            fragment.arguments = args
            return fragment
        }


    }


    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        val chatToCallController: LCallToChatController
        val wcdb: WCDB
    }

    private val chatToCallController: LCallToChatController by lazy {
        EntryPointAccessors.fromApplication<EntryPoint>(
            ApplicationHelper.instance
        ).chatToCallController
    }

    private val wcdb: WCDB by lazy {
        EntryPointAccessors.fromApplication<EntryPoint>(
            ApplicationHelper.instance
        ).wcdb
    }

    private val viewModel: InviteParticipantsViewModel by viewModels()

    private lateinit var binding: FragmentInviteParticipantsBinding
    private lateinit var attendeeAdapter: UserMeetingAttendeeAdapter

    // Define the launcher as a member variable
    private lateinit var attendeeSelectorActivityResultLauncher: ActivityResultLauncher<Intent>


    var eid: String? = null
    var isLiveStream: Boolean = false
    var channelName: String = ""
    private var originalMeetingName: String = ""
    var meetingId: Int? = null
    private var isNeededToBringCallActivityBack: Boolean = false
    private var existedUsersIds = mutableListOf<String>()
    var actionType: Int? = -1
    var roomId: String = ""
    var callName: String = ""
    var conversationId: String? = null
    var callType: String? = null
    var mKey: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        actionType = arguments?.getInt(
            InviteParticipantsActivity.EXTRA_ACTION_TYPE,
            InviteParticipantsActivity.NEW_REQUEST_ACTION_TYPE_INVITE
        ) ?: InviteParticipantsActivity.NEW_REQUEST_ACTION_TYPE_INVITE

        channelName =
            arguments?.getString(InviteParticipantsActivity.EXTRA_CHANNEL_NAME)
                ?: ""
        originalMeetingName =
            arguments?.getString(InviteParticipantsActivity.EXTRA_MEETING_NAME)
                ?: ""
        isNeededToBringCallActivityBack = arguments?.getBoolean(
            InviteParticipantsActivity.EXTRA_SHOULD_BRING_CALL_SCREEN_BACK
        ) ?: false

        eid =
            arguments?.getString(InviteParticipantsActivity.EXTRA_EID)
        isLiveStream = arguments?.getBoolean(
            InviteParticipantsActivity.EXTRA_IS_LIVE_STREAM
        ) ?: false

        existedUsersIds = arguments?.getStringArrayList(
            InviteParticipantsActivity.EXTRA_EXCLUDE_IDS
        )?.toMutableList() ?: mutableListOf()

        roomId = arguments?.getString(InviteParticipantsActivity.EXTRA_ROOM_ID)
            ?: ""

        callName = arguments?.getString(InviteParticipantsActivity.EXTRA_CALL_NAME)
            ?: ""

        conversationId = arguments?.getString(
            InviteParticipantsActivity.EXTRA_CONVERSATION_ID
        )

        callType = arguments?.getString(
            InviteParticipantsActivity.EXTRA_CALL_TYPE
        )

        mKey = arguments?.getByteArray(
            InviteParticipantsActivity.EXTRA_CALL_MKEY
        )

    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentInviteParticipantsBinding.inflate(
            inflater, container, false
        )

        // Initialize the launcher in onCreateView or onViewCreated
        attendeeSelectorActivityResultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // Handle the result here
                // 从 Intent 中获取已选中的成员ID数组
                val selectedMemberIds = result.data?.getStringArrayListExtra("selected_member_ids")

                if (selectedMemberIds != null) {
                    // 过滤掉 existedUsersIds 中的用户，只保留新增的用户
                    val newMemberIds = selectedMemberIds.filter { id ->
                        !existedUsersIds.contains(id)
                    }

                    if (newMemberIds.isNotEmpty()) {
                        // 批量查询新成员的联系人信息
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val contactors = wcdb.getContactorsFromAllTable(newMemberIds)
                                val newMembers = contactors.map { contactor ->
                                    // 将 ContactorModel 转换为 GroupMemberModel
                                    GroupMemberModel(
                                        name = contactor.getDisplayNameForUI(),
                                        uid = contactor.id,
                                        avatarUrl = contactor.avatar?.getContactAvatarData()?.getContactAvatarUrl(),
                                        avatarEncKey = contactor.avatar?.getContactAvatarData()?.encKey,
                                        sortLetters = contactor.getDisplayNameForUI().getFirstLetter(),
                                        role = contactor.groupMemberContactor?.groupRole ?: GROUP_ROLE_MEMBER,
                                        isSelected = false,
                                        checkBoxEnable = true,
                                        showCheckBox = false,
                                        letterName = contactor.getDisplayNameWithoutRemarkForUI()
                                    )
                                }

                                withContext(Dispatchers.Main) {
                                    // 设置新成员为可移除
                                    newMembers.forEach { it.isRemovable = true }

                                    // 合并现有成员和新成员，按ID去重
                                    val existingMembers = viewModel.getAttendees() ?: emptyList()
                                    val allMembers = (existingMembers + newMembers).distinctBy { it.uid }

                                    viewModel.setAttendees(allMembers)
                                }
                            } catch (e: Exception) {
                                L.e { "Failed to fetch new members: ${e.message}" }
                            }
                        }
                    }
                }
            }
        }

        return binding.root
    }

    private var transformedList: MutableList<AttendeeListItem> = mutableListOf()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        observeData()
    }

    private fun setupUI() {

        val toolbar: Toolbar = binding.attendeeListPageToolbar
        (activity as AppCompatActivity).setSupportActionBar(toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_close)
        toolbar.setNavigationOnClickListener {
            activity?.onBackPressed()
        }

        attendeeAdapter =
            UserMeetingAttendeeAdapter(object : AttendeeClickListener {
                override fun onRemoveAttendanceClicked(attendee: GroupMemberModel) {
                    viewModel.removeAttendee(attendee)
                }
            }, true)
        binding.attendeeRecyclerView.layoutManager =
            LinearLayoutManager(requireContext())
        binding.attendeeRecyclerView.adapter = attendeeAdapter

        binding.instantMeetingTitleInput.doOnTextChanged() { text, start, before, count ->
            viewModel.setMeetingName(text.toString())
            setupClearButtonForTitleInput(text.toString())
        }

        binding.instantMeetingTitleInput.setOnTouchListener { v, event ->
            // Check if the touch event occurred on the end drawable
            if (event.action == MotionEvent.ACTION_UP) {
                val drawableRight =
                    binding.instantMeetingTitleInput.compoundDrawables[2]
                if (drawableRight != null) {
                    val clearButtonStart =
                        binding.instantMeetingTitleInput.right - drawableRight.bounds.width() - ViewUtil.dpToPx(
                            16
                        )
                    if (event.rawX >= clearButtonStart) {
                        binding.instantMeetingTitleInput.text.clear()  // Clear the editText content
                        return@setOnTouchListener true
                    }
                }
            }
            return@setOnTouchListener false
        }

        binding.btnAddMember.setOnClickListener {
            val intent = Intent(
                requireActivity(), GroupSelectMemberActivity::class.java
            ).apply {
                putExtra(
                    GroupSelectMemberActivity.EXTRA_TYPE,
                    GroupSelectMemberActivity.TYPE_CALL_ADD_MEMBER
                )

                val attendees = viewModel.getAttendees()
                val memberIds = if (attendees.isNullOrEmpty()) {
                    ArrayList(existedUsersIds) // 只有 existedUsersIds
                } else {
                    // 合并 existedUsersIds 和新增的 attendees
                    val allMemberIds = existedUsersIds.toMutableList()
                    allMemberIds.addAll(attendees.mapNotNull { it.uid })
                    ArrayList(allMemberIds)
                }

                putExtra(
                    GroupSelectMemberActivity.EXTRA_SELECTED_MEMBER_IDS,
                    memberIds
                )
            }
            attendeeSelectorActivityResultLauncher.launch(intent)
        }

        binding.labelMembers.text = ResUtils.getString(R.string.members)
        binding.btnAddMember.text =
            ResUtils.getString(R.string.install_call_btn_add)

        if (actionType == InviteParticipantsActivity.NEW_REQUEST_ACTION_TYPE_INVITE) {
            binding.toolbarTitle.text =
                ResUtils.getString(R.string.invite_participants)
            binding.attendeePageActionInvite.text =
                ResUtils.getString(R.string.meetings_action_invite)
        }
        viewModel.setMeetingName(originalMeetingName)
        binding.instantMeetingTitleInput.setText(originalMeetingName)

        binding.attendeePageActionInvite.setOnClickListener {
            activity?.onBackPressed()

            val idsWithPlusPrefix =
                viewModel.getAttendees()?.map { it.uid ?: "" } ?: listOf()

            L.i { "attendeePageActionInvite meetingName=$originalMeetingName, channelName=$channelName, idsWithPlusPrefix=$idsWithPlusPrefix" }


            val inputMeetingName = viewModel.getMeetingName()
            if (inputMeetingName.isEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val defaultMeetingName = "${viewModel.getMyDisplayName(requireContext(), globalServices.myId)} Meeting"
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            binding.instantMeetingTitleInput.setText(defaultMeetingName)
                        }
                    }
                }
            }

            if (actionType == InviteParticipantsActivity.NEW_REQUEST_ACTION_TYPE_INVITE) {
                L.i { "[Call]: InviteParticipant NEW_REQUEST_ACTION_TYPE_INVITE idsWithPlusPrefix:$idsWithPlusPrefix" }
//                L.i { "[Call]: InviteParticipant NEW_REQUEST_ACTION_TYPE_INVITE inputMeetingName:$inputMeetingName" }
//                L.i { "[Call]: InviteParticipant NEW_REQUEST_ACTION_TYPE_INVITE meetingName:${viewModel.getMeetingName()}" }
                chatToCallController.inviteCall(roomId, callName, callType, mKey, ArrayList(idsWithPlusPrefix), conversationId)
            }
        }

        binding.labelMembers.setOnClickListener {}
    }

    private fun observeData() {
        viewModel.attendees.observe(viewLifecycleOwner) { attendeesList ->

            if (attendeesList.isEmpty()) {
                binding.actionButtonArea.visibility = View.INVISIBLE
            } else {
                binding.actionButtonArea.visibility = View.VISIBLE
            }

            transformedList = convertGroupMembersToAttendeeList(attendeesList)
            attendeeAdapter.submitList(transformedList)
        }
    }

    private fun setupClearButtonForTitleInput(text: String?) {
        if (text.isNullOrEmpty()) {
            binding.instantMeetingTitleInput.setCompoundDrawablesRelativeWithIntrinsicBounds(
                null, null, null, null
            )
        } else {
            val closeDrawable = ContextCompat.getDrawable(
                binding.instantMeetingTitleInput.context,
                R.drawable.circled_close_f
            )
            binding.instantMeetingTitleInput.setCompoundDrawablesRelativeWithIntrinsicBounds(
                null, null, closeDrawable, null
            )
        }
    }

    private fun convertGroupMembersToAttendeeList(
        attendeesList: List<GroupMemberModel>,
    ): MutableList<AttendeeListItem> {
        val transformedList = mutableListOf<AttendeeListItem>()
        attendeesList.forEach { attendee ->
            transformedList.add(
                AttendeeListItem.AttendeeItem(
                    attendee
                )
            )
        }
        return transformedList
    }
}