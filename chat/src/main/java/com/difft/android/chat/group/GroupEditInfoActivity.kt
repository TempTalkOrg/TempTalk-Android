package com.difft.android.chat.group

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ChatActivityGroupEditInfoBinding
import com.difft.android.network.group.ChangeGroupSettingsReq
import com.difft.android.network.group.GroupRepo
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
@AndroidEntryPoint
class GroupEditInfoActivity : BaseActivity() {
    val binding: ChatActivityGroupEditInfoBinding by viewbind()
    private val groupId by lazy {
        intent.getStringExtra(KEY_GROUP_ID) ?: ""
    }
    private val groupName by lazy {
        intent.getStringExtra(KEY_GROUP_NAME) ?: ""
    }

    @Inject
    lateinit var groupRepo: GroupRepo

    @Inject
    lateinit var groupUtil: GroupUtil

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ChatActivityGroupEditInfoBinding.inflate(layoutInflater).root)

        binding.ibBack.setOnClickListener { finish() }



        binding.editGroupName.setText(groupName)

        binding.save.setOnClickListener {
            val text = binding.editGroupName.text.toString()
            if (text.length in 1..64) {
                lifecycleScope.launch {
                    try {
                        val response = groupRepo.changeGroupSettings(groupId, ChangeGroupSettingsReq(name = text.toString()))
                        groupUtil.fetchAndSaveSingleGroupInfo(groupId, true)
                        if (response.status == 0) {
                            finish()
                        } else {
                            response.reason?.let { message -> ToastUtil.showLong(message) }
                        }
                    } catch (e: Exception) {
                        L.w { "[GroupEditInfoActivity] changeGroupSettings error: ${e.stackTraceToString()}" }
                        ToastUtil.showLong(R.string.chat_net_error)
                    }
                }
            } else {
                ToastUtil.show(R.string.chat_group_name_too_long)
            }
        }
    }
}