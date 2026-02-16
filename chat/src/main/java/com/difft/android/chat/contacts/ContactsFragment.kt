package com.difft.android.chat.contacts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.difft.android.base.fragment.DisposableManageFragment
import com.difft.android.chat.R
import com.difft.android.chat.contacts.contactsall.ContactsAllFragment
import com.difft.android.chat.contacts.contactsgroup.GroupsFragment
import com.difft.android.chat.databinding.ChatFragmentContactsBinding
import com.difft.android.chat.invite.InviteCodeActivity

class ContactsFragment : DisposableManageFragment() {
    private lateinit var binding: ChatFragmentContactsBinding

    private var hasInit = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = ChatFragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 设置添加好友按钮的点击监听
        binding.ibAddContact.setOnClickListener {
            InviteCodeActivity.startActivity(requireActivity())
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasInit.not()) {
            hasInit = true
            initPage()
        }
    }

    private fun initPage() {
        binding.viewpager.apply {
            adapter = object : FragmentStateAdapter(this@ContactsFragment) {
                private val fragmentClasses = listOf(
                    ContactsAllFragment::class.java, GroupsFragment::class.java
                )

                override fun getItemCount(): Int = fragmentClasses.size

                override fun createFragment(position: Int): Fragment {
                    val fragmentClass = fragmentClasses[position]
                    return fragmentClass.newInstance()
                }
            }

            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    selectPagerButton(position)
                }
            })
        }

        binding.includeContactors.textViewTitle.text = getString(R.string.contact_all)
        binding.includeGroups.textViewTitle.text = getString(R.string.contact_groups)

        selectPagerButton(binding.viewpager.currentItem)
        run {
            val onClickListener = View.OnClickListener {
                when (it.id) {
                    binding.includeContactors.root.id -> {
                        selectPagerButton(0)
                        binding.viewpager.setCurrentItem(0, true)
                    }

                    binding.includeGroups.root.id -> {
                        selectPagerButton(1)
                        binding.viewpager.setCurrentItem(1, true)
                    }
                }
            }
            binding.includeContactors.root.setOnClickListener(onClickListener)
            binding.includeGroups.root.setOnClickListener(onClickListener)
        }
    }

    private fun selectPagerButton(index: Int) {
        val buttons = arrayOf(binding.includeContactors.root, binding.includeGroups.root)
        buttons.forEach {
            val isViewSelected = buttons.indexOf(it) == index
            it.isSelected = isViewSelected
        }
    }
}