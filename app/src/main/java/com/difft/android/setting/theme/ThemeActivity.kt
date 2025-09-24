package com.difft.android.setting.theme

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.BaseActivity
import com.difft.android.base.user.UserManager
import com.difft.android.databinding.ActivityThemeBinding
import com.difft.android.base.widget.DialogXUtil
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject


@AndroidEntryPoint
class ThemeActivity : BaseActivity() {
    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, ThemeActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityThemeBinding by viewbind()

    @Inject
    lateinit var userManager: UserManager

    private val mAdapter: ThemeAdapter by lazy {
        object : ThemeAdapter() {
            override fun onItemClicked(themeData: ThemeData, position: Int) {
                when (themeData.theme) {
                    AppCompatDelegate.MODE_NIGHT_YES -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                        userManager.update {
                            this.theme = AppCompatDelegate.MODE_NIGHT_YES
                        }
                    }

                    AppCompatDelegate.MODE_NIGHT_NO -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                        userManager.update {
                            this.theme = AppCompatDelegate.MODE_NIGHT_NO
                        }
                    }

                    else -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                        userManager.update {
                            this.theme = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        }
                    }
                }
                DialogXUtil.initDialog(this@ThemeActivity, themeData.theme)

                updateSelectedTheme(themeData.theme)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initView()
    }

    private lateinit var themeDataList: List<ThemeData>

    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }

        mBinding.rvLanguage.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = mAdapter
            itemAnimator = null
        }

        themeDataList = mutableListOf<ThemeData>().apply {
            this.add(ThemeData(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, getString(com.difft.android.chat.R.string.me_theme_system),false))
            this.add(ThemeData(AppCompatDelegate.MODE_NIGHT_NO, getString(com.difft.android.chat.R.string.me_theme_light),false))
            this.add(ThemeData(AppCompatDelegate.MODE_NIGHT_YES, getString(com.difft.android.chat.R.string.me_theme_dark),false))
        }

        val currentTheme = userManager.getUserData()?.theme ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        updateSelectedTheme(currentTheme)
    }

    private fun updateSelectedTheme(selectedTheme: Int) {
        themeDataList.forEach { it.selected = false }
        themeDataList.find { it.theme == selectedTheme }?.selected = true
        mAdapter.submitList(themeDataList.toList())
    }
}