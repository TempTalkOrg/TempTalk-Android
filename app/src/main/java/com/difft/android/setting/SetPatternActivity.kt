package com.difft.android.setting

import com.difft.android.login.SecurityResultNonce
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import com.difft.android.base.widget.PatternLockView
import com.difft.android.login.PatternCredential
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.sidechrome.sideChromeBar
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

    override fun participatesInSideChrome(): Boolean = true

    override fun padsRootForSideChrome(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecurityResultNonce.resultData(intent)?.let { setResult(RESULT_CANCELED, it) }
        initView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isConfirmStep) {
                    
                    showFirstStep()
                } else {
                    finish()
                }
            }
        })
    }

    private fun initView() {
        sideChromeBar().setBack(mBinding.ibBack, { onBackPressedDispatcher.onBackPressed() })

        showFirstStep()
        SetPatternLayoutPolicy(mBinding).attach()

        mBinding.patternLockView.addPatternLockListener(object : PatternLockView.PatternLockViewListener {
            override fun onStarted() {
                showSubtitle()
            }

            override fun onProgress(progressPattern: MutableList<PatternLockView.Dot>) {
                
            }

            override fun onComplete(pattern: MutableList<PatternLockView.Dot>) {
                
                if (pattern.size <= 1) {
                    mBinding.patternLockView.clearPattern()
                    return
                }
                if (!isConfirmStep) {
                    
                    if (pattern.size < 4) {
                        showError(getString(R.string.settings_pattern_too_short))
                        mBinding.patternLockView.clearPattern()
                        return
                    }

                    firstPattern = pattern.toList()
                    showConfirmStep()
                } else {
                    
                    if (pattern.map { it.id } == firstPattern?.map { it.id }) {
                        
                        savePattern(pattern)

                        ToastUtil.show(getString(R.string.settings_pattern_success_title))
                        finish()
                    } else {
                        showError(getString(R.string.settings_pattern_not_match))
                        mBinding.patternLockView.clearPattern()
                    }
                }
            }

            override fun onCleared() {
                
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
        
        val patternHash = PatternCredential.create(pattern.map { it.id }).format()

        userManager.update {
            this.pattern = patternHash
            this.patternShowPath = true 
        }
    }
}
