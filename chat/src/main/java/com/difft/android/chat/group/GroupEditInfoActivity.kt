package com.difft.android.chat.group

import android.os.Bundle
import com.difft.android.base.BaseActivity
import com.difft.android.base.utils.RxUtil
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ChatActivityGroupEditInfoBinding
import com.difft.android.network.group.ChangeGroupSettingsReq
import com.difft.android.network.group.GroupRepo
import com.hi.dhl.binding.viewbind
import com.kongzue.dialogx.dialogs.PopTip
import com.kongzue.dialogx.dialogs.TipDialog
import dagger.hilt.android.AndroidEntryPoint
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import javax.inject.Inject

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ChatActivityGroupEditInfoBinding.inflate(layoutInflater).root)

        binding.ibBack.setOnClickListener { finish() }



        binding.editGroupName.setText(groupName)

        binding.save.setOnClickListener {
            val text = binding.editGroupName.text
            if (text.length in 1..64) {
                groupRepo.changeGroupSettings(groupId, ChangeGroupSettingsReq(name = text.toString()))
                    .toObservable()
                    .concatMap { response ->
                        GroupUtil.fetchAndSaveSingleGroupInfo(ApplicationDependencies.getApplication(), groupId, true).map {
                            it to response
                        }
                    }
                    .compose(RxUtil.getSchedulerComposer())
                    .to(RxUtil.autoDispose(this))
                    .subscribe({
                        if (it.second.status == 0) {
                            finish()
                        } else {
                            TipDialog.show(it.second.reason)
                        }
                    }, {
                        it.printStackTrace()
                        TipDialog.show(R.string.chat_net_error)
                    })
            } else {
                PopTip.show(R.string.chat_group_name_too_long)
            }
        }
    }
}