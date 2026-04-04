package com.difft.android.chat.group

import android.app.Activity
import com.difft.android.base.log.lumberjack.L
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.difft.app.database.members
import com.difft.android.chat.R
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.databinding.ActivitySearchGroupMemberBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.difft.app.database.WCDB
import org.difft.app.database.models.GroupMemberContactorModel
import javax.inject.Inject

@AndroidEntryPoint
class GroupMembersSearchActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity, groupId: String) {
            val intent = Intent(activity, GroupMembersSearchActivity::class.java)
            intent.putExtra("groupId", groupId)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivitySearchGroupMemberBinding by viewbind()

    @Inject
    lateinit var wcdb: WCDB

    @Inject
    lateinit var groupUtil: GroupUtil

    private val groupId: String by lazy {
        intent.getStringExtra("groupId") ?: ""
    }

    private val searchFlow = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val mSearchGroupMemberAdapter: SearchGroupMemberAdapter by lazy {
        object : SearchGroupMemberAdapter() {
            override fun onContactClicked(contact: GroupMemberContactorModel, position: Int) {
                ContactDetailActivity.startActivity(this@GroupMembersSearchActivity, contact.id)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()
    }

    @OptIn(FlowPreview::class)
    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }

        mBinding.edittextSearchInput.addTextChangedListener {
            searchFlow.tryEmit(it.toString().trim())
        }

        lifecycleScope.launch {
            searchFlow
                .debounce(300)
                .distinctUntilChanged()
                .collect {
                    resetButtonClear()
                    if (it.isNotEmpty()) {
                        search(it)
                    } else {
                        showNoResults(getString(R.string.search_messages_default_tips))
                    }
                }
        }

        mBinding.recyclerviewChatHistory.apply {
            this.layoutManager = LinearLayoutManager(this@GroupMembersSearchActivity)
            itemAnimator = null
            this.adapter = mSearchGroupMemberAdapter
        }

        mBinding.buttonClear.setOnClickListener {
            mBinding.edittextSearchInput.text = null
        }
    }

    private fun search(key: String) {
        lifecycleScope.launch {
            try {
                val group = groupUtil.getSingleGroupInfo(groupId)
                val results = group?.members?.filter { member ->
                    member.displayName?.contains(key, true) == true ||
                            member.remark?.contains(key, true) == true ||
                            member.id?.contains(key, true) == true
                } ?: emptyList()
                if (results.isEmpty()) {
                    showNoResults(getString(R.string.search_no_results_found))
                } else {
                    mBinding.recyclerviewChatHistory.visibility = View.VISIBLE
                    mSearchGroupMemberAdapter.submitList(results)
                    mBinding.tvTips.visibility = View.GONE
                }
            } catch (e: Exception) {
                showNoResults(getString(R.string.search_no_results_found))
                L.w { "[GroupMembersSearchActivity] search error: ${e.stackTraceToString()}" }
            }
        }
    }

    private fun resetButtonClear() {
        val key = mBinding.edittextSearchInput.text.toString().trim()
        mBinding.buttonClear.animate().apply {
            cancel()
            val toAlpha = if (key.isNotEmpty()) 1.0f else 0f
            alpha(toAlpha)
        }
    }

    private fun showNoResults(tipContent: String) {
        mBinding.recyclerviewChatHistory.visibility = View.GONE
        mSearchGroupMemberAdapter.submitList(emptyList())
        mBinding.tvTips.visibility = View.VISIBLE
        mBinding.tvTips.text = tipContent
    }
}