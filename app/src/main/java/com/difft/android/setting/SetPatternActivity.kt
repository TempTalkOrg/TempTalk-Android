package com.difft.android.setting

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import com.difft.android.base.widget.PatternLockView
import com.difft.android.login.PasscodeUtil
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.base.user.UserManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.databinding.ActivitySetPatternBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SetPatternActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, SetPatternActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivitySetPatternBinding by viewbind()

    @Inject
    lateinit var userManager: UserManager

    private var firstPattern: List<PatternLockView.Dot>? = null
    private var isConfirmStep = false

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 禁用横屏，强制竖屏显示
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        initView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isConfirmStep) {
                    // Back to first step
                    showFirstStep()
                } else {
                    finish()
                }
            }
        })
    }

    private fun initView() {
        mBinding.ibBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        showFirstStep()

        mBinding.patternLockView.addPatternLockListener(object : PatternLockView.PatternLockViewListener {
            override fun onStarted() {
                // 重新绘制时显示subtitle，隐藏error
                showSubtitle()
            }

            override fun onProgress(progressPattern: MutableList<PatternLockView.Dot>) {
                // Pattern drawing in progress
            }

            override fun onComplete(pattern: MutableList<PatternLockView.Dot>) {
                if (!isConfirmStep) {
                    // First step - record pattern
                    if (pattern.size < 4) {
                        showError(getString(R.string.settings_pattern_too_short))
                        mBinding.patternLockView.clearPattern()
                        return
                    }

                    firstPattern = pattern.toList()
                    showConfirmStep()
                } else {
                    // Second step - confirm pattern
                    val currentPatternString = patternToString(pattern)
                    val firstPatternString = patternToString(firstPattern)

                    if (currentPatternString == firstPatternString) {
                        // Pattern confirmed, save it
                        savePattern(pattern)

                        // 显示成功Toast并关闭页面
                        ToastUtil.show(getString(R.string.settings_pattern_success_title))
                        finish()
                    } else {
                        showError(getString(R.string.settings_pattern_not_match))
                        mBinding.patternLockView.clearPattern()
                    }
                }
            }

            override fun onCleared() {
                // Pattern cleared
            }
        })
    }

    private fun showFirstStep() {
        isConfirmStep = false
        mBinding.tvTitle.text = getString(R.string.settings_pattern_draw_title)
        mBinding.tvSubtitle.text = getString(R.string.settings_pattern_draw_subtitle)
        showSubtitle()
        mBinding.patternLockView.clearPattern()
    }

    private fun showConfirmStep() {
        isConfirmStep = true
        mBinding.tvTitle.text = getString(R.string.settings_pattern_confirm_title)
        mBinding.tvSubtitle.text = getString(R.string.settings_pattern_confirm_subtitle)
        showSubtitle()
        mBinding.patternLockView.clearPattern()
    }

    private fun showSubtitle() {
        mBinding.tvSubtitle.visibility = View.VISIBLE
        mBinding.tvError.visibility = View.GONE
    }

    private fun showError(message: String) {
        mBinding.tvError.text = message
        mBinding.tvSubtitle.visibility = View.GONE
        mBinding.tvError.visibility = View.VISIBLE
    }

    private fun savePattern(pattern: List<PatternLockView.Dot>) {
        val patternString = patternToString(pattern)
        val (salt, hashedPattern) = PasscodeUtil.createSaltAndHashByPassword(patternString)
        val patternHash = "${hashedPattern}:${salt}"

        userManager.update {
            this.pattern = patternHash
            this.patternShowPath = true // Default to show path
        }
    }

    private fun patternToString(pattern: List<PatternLockView.Dot>?): String {
        return pattern?.joinToString(",") { it.id.toString() } ?: ""
    }
}
