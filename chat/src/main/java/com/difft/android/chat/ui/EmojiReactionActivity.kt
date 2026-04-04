package com.difft.android.chat.ui

import android.app.Activity
import com.difft.android.base.log.lumberjack.L
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.difft.android.base.BaseActivity

import com.difft.android.base.utils.globalServices
import org.difft.app.database.reactions
import org.difft.app.database.wcdb
import com.difft.android.chat.databinding.ActivityEmojiReactionBinding
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Reaction
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import com.google.android.material.tabs.TabLayoutMediator
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import org.difft.app.database.models.DBMessageModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class EmojiReactionActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity, groupId: String?, contactID: String?, messageId: String) {
            val intent = Intent(activity, EmojiReactionActivity::class.java)
            intent.putExtra("groupId", groupId)
            intent.putExtra("contactID", contactID)
            intent.putExtra("messageId", messageId)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityEmojiReactionBinding by viewbind()


    val myID: String by lazy {
        globalServices.myId
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding.ibBack.setOnClickListener { finish() }

        val contactID = intent.getStringExtra("contactID")
        val groupId = intent.getStringExtra("groupId")
        val messageId = intent.getStringExtra("messageId")

        val forWhat: For? = if (contactID != null) {
            For.Account(contactID)
        } else if (groupId != null) {
            For.Group(groupId)
        } else {
            null
        }

        if (forWhat == null || messageId == null) return

        lifecycleScope.launch {
            try {
                val reactions = withContext(Dispatchers.IO) {
                    wcdb.message.getFirstObject(DBMessageModel.id.eq(messageId))?.reactions()?.groupBy { it.emoji }
                }
                if (!reactions.isNullOrEmpty()) {
                    initViewPager(reactions)
                }
            } catch (e: Exception) {
                L.w { "[EmojiReactionActivity] error: ${e.stackTraceToString()}" }
            }
        }
    }

    private fun initViewPager(emojis: Map<String, List<Reaction>>) {
//        var emojis: Map<String, List<Reaction>>? = null


        val titles = mutableListOf<String>()
        emojis.forEach {
            titles.add(it.key + "(" + it.value.size + ")")
        }

        mBinding.viewpager.adapter = MyFragmentStateAdapter(this, emojis, titles)

        TabLayoutMediator(mBinding.tabLayout, mBinding.viewpager) { tab, position ->
            tab.text = titles[position]
        }.attach()

        mBinding.tabLayout.addOnTabSelectedListener(object : OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.let {
                    // 获取选中标签的左侧位置
                    val tabLeft = it.view.left
                    val scrollViewWidth = mBinding.scrollTab.width
                    val tabWidth = it.view.width
                    val scrollX = tabLeft - (scrollViewWidth - tabWidth) / 2
                    // 滚动 ScrollView 以将选中标签居中
                    mBinding.scrollTab.smoothScrollTo(scrollX, 0)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {
            }

            override fun onTabReselected(tab: TabLayout.Tab?) {
            }

        })
    }


    private inner class MyFragmentStateAdapter(
        activity: FragmentActivity,
        private val emojis: Map<String, List<Reaction>>,
        private val tiles: List<String>
    ) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = emojis.size

        override fun createFragment(position: Int): Fragment {
            val users = emojis[tiles[position].split("(")[0]]?.map { reaction -> reaction.uid }?.distinct()
            return EmojiReactionTabFragment.newInstance(users?.let { ArrayList(it) })
        }
    }
}