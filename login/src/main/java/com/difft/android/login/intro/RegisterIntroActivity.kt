package com.difft.android.login.intro

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.login.ContactProfileSettingActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class RegisterIntroActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity) {
            activity.startActivity(Intent(activity, RegisterIntroActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        L.i { "[RegisterIntro] enter onboarding" }

        val composeView = ComposeView(this).apply {
            setContent {
                DifftTheme {
                    RegisterIntroScreen(
                        onFinishIntro = {
                            L.i { "[RegisterIntro] finish intro -> ContactProfileSetting" }
                            ContactProfileSettingActivity.startActivity(
                                this@RegisterIntroActivity,
                                ContactProfileSettingActivity.BUNDLE_VALUE_FROM_SIGN_UP
                            )
                            finish()
                        },
                        onBackToSignUp = {
                            L.i { "[RegisterIntro] back to sign up" }
                            finish()
                        },
                    )
                }
            }
        }
        setContentView(composeView)
    }
}
