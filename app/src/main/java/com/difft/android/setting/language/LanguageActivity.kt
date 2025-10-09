package com.difft.android.setting.language

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.base.utils.LanguageData
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.ResUtils
import com.difft.android.databinding.ActivityLanguageBinding
import com.hi.dhl.binding.viewbind
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ComposeDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlin.system.exitProcess


@AndroidEntryPoint
class LanguageActivity : BaseActivity() {


    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, LanguageActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityLanguageBinding by viewbind()

    private val mAdapter: LanguageAdapter by lazy {
        object : LanguageAdapter() {
            override fun onItemClicked(languageData: LanguageData, position: Int) {
                if (languageData.locale == LanguageUtils.getLanguage(this@LanguageActivity)) return

                val title = ResUtils.getLocaleStringResource(this@LanguageActivity, languageData.locale, R.string.language_restart_required)
                val content = ResUtils.getLocaleStringResource(this@LanguageActivity, languageData.locale, R.string.language_restart_tips)
                val ok = ResUtils.getLocaleStringResource(this@LanguageActivity, languageData.locale, R.string.language_restart)
                val cancel = ResUtils.getLocaleStringResource(this@LanguageActivity, languageData.locale, R.string.language_restart_later)

                ComposeDialogManager.showMessageDialog(
                    context = this@LanguageActivity,
                    title = title,
                    message = content,
                    confirmText = ok,
                    cancelText = cancel,
                    onConfirm = {
                        LanguageUtils.saveLanguage(this@LanguageActivity, languageData.locale)

                        this@LanguageActivity.packageManager.getLaunchIntentForPackage(this@LanguageActivity.packageName)?.apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            this@LanguageActivity.startActivity(this)
                        }
                        android.os.Process.killProcess(android.os.Process.myPid())
                        exitProcess(0)
                    },
                    onCancel = {
                        LanguageUtils.locale = LanguageUtils.getLanguage(this@LanguageActivity)
                        LanguageUtils.saveLanguage(this@LanguageActivity, languageData.locale)
                        mAdapter.submitList(LanguageUtils.getLanguageList(this@LanguageActivity))
                    }
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initView()
    }

    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }

        mBinding.rvLanguage.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = mAdapter
            itemAnimator = null
        }

        mAdapter.submitList(LanguageUtils.getLanguageList(this))
    }
}