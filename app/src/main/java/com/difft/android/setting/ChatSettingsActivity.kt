package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.databinding.ActivityChatSettingsBinding
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.PrivateConfigsRequestBody
import com.difft.android.network.requests.ProfileRequestBody
import com.hi.dhl.binding.viewbind
import com.kongzue.dialogx.dialogs.PopTip
import com.kongzue.dialogx.dialogs.WaitDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class ChatSettingsActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, ChatSettingsActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityChatSettingsBinding by viewbind()

    @Inject
    lateinit var userManager: UserManager

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var httpClient: ChativeHttpClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding.ibBack.setOnClickListener { finish() }

        initView()
    }

    private fun initView() {
        mBinding.switchSaveToPhotos.isChecked = (userManager.getUserData()?.saveToPhotos == true)
        mBinding.switchSaveToPhotos.setOnClickListener {
            val newValue = mBinding.switchSaveToPhotos.isChecked
            val profileRequestBody = ProfileRequestBody(
                privateConfigs = PrivateConfigsRequestBody(saveToPhotos = newValue)
            )

            WaitDialog.show(this@ChatSettingsActivity, "")
            lifecycleScope.launch {
                try {
                    val response = withContext(Dispatchers.IO) {
                        httpClient.httpService.fetchSetProfile(
                            baseAuth = SecureSharedPrefsUtil.getBasicAuth(),
                            profileRequestBody = profileRequestBody
                        ).blockingGet()
                    }

                    WaitDialog.dismiss()
                    if (response.isSuccess()) {
                        userManager.update { saveToPhotos = newValue }
                    } else {
                        mBinding.switchSaveToPhotos.isChecked = !newValue
                        PopTip.show(response.reason)
                    }
                } catch (e: Exception) {
                    L.e { "[ChatSettingsActivity] set saveToPhotos failed: ${e.stackTraceToString()}" }
                    WaitDialog.dismiss()
                    PopTip.show(e.message)
                    mBinding.switchSaveToPhotos.isChecked = !newValue
                }
            }
        }
    }
}