package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.base.user.UserManager
import com.difft.android.databinding.ActivitySetPasscodeTimeOutBinding
import com.difft.android.databinding.LayoutItemPasscodeTimeoutBinding
import com.difft.android.login.PasscodeUtil
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import util.TimeUtils
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@AndroidEntryPoint
class SetPasscodeTimeoutActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, SetPasscodeTimeoutActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivitySetPasscodeTimeOutBinding by viewbind()

    @Inject
    lateinit var userManager: UserManager

    private val timeoutAdapter: TimeoutAdapter by lazy {
        TimeoutAdapter { selectedItem ->
            syncAndSaveTimeout(selectedItem)
        }
    }

    private var isUpdating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initView()
    }

    private fun initView() {
        mBinding.ibBack.setOnClickListener {
            finish()
        }

        mBinding.rvTimeout.apply {
            this.adapter = timeoutAdapter
            this.layoutManager = LinearLayoutManager(this@SetPasscodeTimeoutActivity)
            itemAnimator = null
        }

        val selectedTimeout = userManager.getUserData()?.passcodeTimeout
        val timeoutList = PasscodeUtil.TIMEOUT_LIST.map {
            TimeoutItem(
                it,
                if (it == 0) getString(R.string.settings_screen_lock_timeout_instant) else TimeUtils.millis2FitTimeSpan(it.seconds.inWholeMilliseconds, 3, false),
                selectedTimeout == it
            )
        }

        timeoutAdapter.submitList(timeoutList)
    }

    private fun syncAndSaveTimeout(timeoutItem: TimeoutItem) {
        if (isUpdating) return // 防抖

        isUpdating = true
        val passcode = userManager.getUserData()?.passcode ?: return
        userManager.update {
            this.passcode = passcode
            this.passcodeTimeout = timeoutItem.time
        }
        updateSelectedItem(timeoutItem)
        PasscodeUtil.disableScreenLock = true
        isUpdating = false
    }

    private fun updateSelectedItem(selectedItem: TimeoutItem) {
        val updatedList = timeoutAdapter.currentList.map { item ->
            item.copy(isSelected = item.time == selectedItem.time)
        }
        timeoutAdapter.submitList(updatedList.toList())
    }
}

data class TimeoutItem(
    val time: Int,
    val displayTime: String,
    var isSelected: Boolean = false
)

class TimeoutAdapter(private val onItemClick: (TimeoutItem) -> Unit) : ListAdapter<TimeoutItem, TimeoutAdapter.TimeoutViewHolder>(
    object : DiffUtil.ItemCallback<TimeoutItem>() {
        override fun areItemsTheSame(oldItem: TimeoutItem, newItem: TimeoutItem): Boolean {
            return oldItem.time == newItem.time
        }

        override fun areContentsTheSame(oldItem: TimeoutItem, newItem: TimeoutItem): Boolean {
            return oldItem == newItem
        }
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TimeoutViewHolder {
        val binding = LayoutItemPasscodeTimeoutBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TimeoutViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TimeoutViewHolder, position: Int) {
        holder.bind(getItem(position), onItemClick)
    }

    class TimeoutViewHolder(private val binding: LayoutItemPasscodeTimeoutBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TimeoutItem, onItemClick: (TimeoutItem) -> Unit) {
            binding.tvTitle.text = item.displayTime
            binding.ivSelected.isVisible = item.isSelected

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}
