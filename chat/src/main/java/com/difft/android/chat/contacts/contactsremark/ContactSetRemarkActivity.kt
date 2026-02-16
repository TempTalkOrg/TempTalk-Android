package com.difft.android.chat.contacts.contactsremark

import android.app.Activity
import com.difft.android.base.log.lumberjack.L
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import org.difft.app.database.getContactorFromAllTable
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.databinding.ChatActivityContactRemarkBinding
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.ConversationSetRequestBody
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.difft.app.database.WCDB
import java.util.Optional
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
@AndroidEntryPoint
class ContactSetRemarkActivity : BaseActivity() {

    companion object {
        private const val BUNDLE_KEY_CONTACT_ID = "BUNDLE_KEY_CONTACT_ID"

        fun startActivity(activity: Activity, contactID: String) {
            val intent = Intent(activity, ContactSetRemarkActivity::class.java)
            intent.contactID = contactID
            activity.startActivity(intent)
        }

        private var Intent.contactID: String?
            get() = getStringExtra(BUNDLE_KEY_CONTACT_ID)
            set(value) {
                putExtra(BUNDLE_KEY_CONTACT_ID, value)
            }
    }

    private val mBinding: ChatActivityContactRemarkBinding by viewbind()

    val token: String by lazy {
        SecureSharedPrefsUtil.getToken()
    }

    private val contactId: String by lazy {
        intent.contactID ?: ""
    }

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var httpClient: ChativeHttpClient

    @Inject
    lateinit var wcdb: WCDB

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()
        initData()
    }

    private fun initView() {
        mBinding.ibBack.setOnClickListener { finish() }

        mBinding.etName.addTextChangedListener {
            refreshClearButton()
        }

        mBinding.btnClear.setOnClickListener {
            mBinding.etName.text = null
        }


        mBinding.btnSave.setOnClickListener {
            val remark = mBinding.etName.text.toString().trim()
            var encryptRemark = ""
            if (remark.isNotEmpty()) {
                val paddedString = (contactId + contactId + contactId).padEnd(32, '+')
                val key = paddedString.toByteArray().copyOf(32)
                encryptRemark = "V1|" + ContactRemarkUtil.encryptRemark(remark.toByteArray(), key)
            }
            mBinding.btnSave.isLoading = true
            httpClient.httpService.fetchConversationSet(SecureSharedPrefsUtil.getBasicAuth(), ConversationSetRequestBody(contactId ?: "", remark = encryptRemark))
                .compose(RxUtil.getSingleSchedulerComposer())
                .to(RxUtil.autoDispose(this))
                .subscribe({
                    mBinding.btnSave.isLoading = false
                    if (it.status == 0) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            ContactorUtil.updateRemark(contactId ?: "", encryptRemark)
                        }
                        finish()
                    } else {
                        it.reason?.let { message -> ToastUtil.show(message) }
                    }
                }) {
                    mBinding.btnSave.isLoading = false
                    it.message?.let { message -> ToastUtil.show(message) }
                }

        }
    }

    private fun refreshClearButton() {
        val etContent = mBinding.etName.text.toString().trim()
//        mBinding.btnSave.isEnabled = !TextUtils.isEmpty(etContent)
        mBinding.btnClear.animate().apply {
            cancel()
            val toAlpha = if (!TextUtils.isEmpty(etContent)) 1.0f else 0f
            alpha(toAlpha)
        }
    }

    private fun initData() {
        Single.fromCallable { Optional.ofNullable(wcdb.getContactorFromAllTable(contactId)) }
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ contact ->
                if (contact.isPresent) {
                    val name = contact.get().getDisplayNameForUI()
                    mBinding.etName.setText(name)
                    mBinding.etName.setSelection(mBinding.etName.text?.length ?: 0)
                }
            }, { error ->
                L.w { "[ContactSetRemarkActivity] initData error: ${error.stackTraceToString()}" }
            })
    }
}