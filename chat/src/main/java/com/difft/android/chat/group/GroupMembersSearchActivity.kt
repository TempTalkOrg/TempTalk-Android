package com.difft.android.chat.group

import android.app.Activity
import com.difft.android.base.log.lumberjack.L
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.BaseActivity
import com.difft.android.base.utils.RxUtil
import org.difft.app.database.members
import com.difft.android.chat.R
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.databinding.ActivitySearchGroupMemberBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.subjects.PublishSubject
import org.difft.app.database.WCDB
import org.difft.app.database.models.GroupMemberContactorModel
import java.util.concurrent.TimeUnit
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

    private val groupId: String by lazy {
        intent.getStringExtra("groupId") ?: ""
    }

    private val searchSubject = PublishSubject.create<String>()

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

    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }

        mBinding.edittextSearchInput.addTextChangedListener {
            searchSubject.onNext(it.toString().trim())
        }

        searchSubject
            .debounce(300, TimeUnit.MILLISECONDS)//防止频繁触发搜索
            .distinctUntilChanged()
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                resetButtonClear()
                if (it.isNotEmpty()) {
                    search(it)
                } else {
                    showNoResults(getString(R.string.search_messages_default_tips))
                }
            }, { L.w { "[GroupMembersSearchActivity] search debounce error: ${it.stackTraceToString()}" } })

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
        GroupUtil.getSingleGroupInfo(this, groupId)
            .map {
                if (it.isPresent) {
                    val result = it.get().members.filter { member ->
                        member.displayName?.contains(key, true) == true ||
                                member.remark?.contains(key, true) == true ||
                                member.id?.contains(key, true) == true
                    }
                    result
                } else {
                    emptyList()
                }
            }
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.isNullOrEmpty()) {
                    showNoResults(getString(R.string.search_no_results_found))
                } else {
                    mBinding.recyclerviewChatHistory.visibility = View.VISIBLE
                    mSearchGroupMemberAdapter.submitList(it)
                    mBinding.tvTips.visibility = View.GONE
                }
            }, {
                showNoResults(getString(R.string.search_no_results_found))
                L.w { "[GroupMembersSearchActivity] search error: ${it.stackTraceToString()}" }
            })
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