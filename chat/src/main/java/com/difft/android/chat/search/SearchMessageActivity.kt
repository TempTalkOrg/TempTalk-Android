package com.difft.android.chat.search


import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.BaseActivity
import com.difft.android.base.utils.RxUtil
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ActivitySearchMessageBinding
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.ui.ChatActivity
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.subjects.PublishSubject
import org.difft.app.database.WCDB
import org.difft.app.database.models.DBMessageModel
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class SearchMessageActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity, conversationId: String, isGroup: Boolean, key: String?) {
            val intent = Intent(activity, SearchMessageActivity::class.java)
            intent.putExtra("conversationId", conversationId)
            intent.putExtra("isGroup", isGroup)
            intent.putExtra("key", key)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivitySearchMessageBinding by viewbind()

    @Inject
    lateinit var wcdb: WCDB

    private val conversationId: String by lazy {
        intent.getStringExtra("conversationId") ?: ""
    }
    private val isGroup: Boolean by lazy {
        intent.getBooleanExtra("isGroup", false)
    }

    private var key: String = ""

    private val searchSubject = PublishSubject.create<String>()

    private val mSearchChatHistoryAdapter: SearchChatHistoryAdapter by lazy {
        object : SearchChatHistoryAdapter(true) {
            override fun onItemClicked(data: SearchChatHistoryViewData, position: Int) {
                if (isGroup) {
                    GroupChatContentActivity.startActivity(this@SearchMessageActivity, conversationId, jumpMessageTimeStamp = data.messageTimeStamp)
                } else {
                    ChatActivity.startActivity(this@SearchMessageActivity, conversationId, jumpMessageTimeStamp = data.messageTimeStamp)
                }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        key = intent.getStringExtra("key") ?: ""

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
                key = it
                resetButtonClear()
                if (key.isNotEmpty()) {
                    search()
                } else {
                    showNoResults(getString(R.string.search_messages_default_tips))
                }
            }, { it.printStackTrace() })

        mBinding.recyclerviewChatHistory.apply {
            this.layoutManager = LinearLayoutManager(this@SearchMessageActivity)
            itemAnimator = null
            this.adapter = mSearchChatHistoryAdapter
        }

        mBinding.buttonClear.setOnClickListener {
            mBinding.edittextSearchInput.text = null
        }

        mBinding.edittextSearchInput.setText(key)
        mBinding.edittextSearchInput.setSelection(key.length)
    }

    private fun search() {
        Single.fromCallable {
            wcdb.message.getAllObjects(
                DBMessageModel.roomId.eq(conversationId).and(
                    DBMessageModel.messageText.upper().like("%${key.uppercase()}%")
                        .and(DBMessageModel.type.notEq(2))
                )
            )
        }
            .concatMap { result ->
                SearchUtils.createSearchChatHistoryViewDataList(result)
            }
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.isNullOrEmpty()) {
                    showNoResults(getString(R.string.search_no_results_found))
                } else {
                    mBinding.recyclerviewChatHistory.visibility = View.VISIBLE
                    mSearchChatHistoryAdapter.submitList(it)
                    mBinding.tvTips.visibility = View.GONE
                }
            }, {
                showNoResults(getString(R.string.search_no_results_found))
                it.printStackTrace()
            })
    }

    private fun resetButtonClear() {
        mBinding.buttonClear.animate().apply {
            cancel()
            val toAlpha = if (key.isNotEmpty()) 1.0f else 0f
            alpha(toAlpha)
        }
    }

    private fun showNoResults(tipContent: String) {
        mBinding.recyclerviewChatHistory.visibility = View.GONE
        mSearchChatHistoryAdapter.submitList(emptyList())
        mBinding.tvTips.visibility = View.VISIBLE
        mBinding.tvTips.text = tipContent
    }
}