package com.difft.android.chat.contacts.contactsdetail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import androidx.fragment.app.commit
import com.difft.android.base.BaseActivity
import com.difft.android.chat.R
import dagger.hilt.android.AndroidEntryPoint

const val BUNDLE_KEY_SOURCE_TYPE = "BUNDLE_KEY_CONTACT_SOURCE_TYPE"
const val BUNDLE_KEY_SOURCE = "BUNDLE_KEY_CONTACT_SOURCE"

@AndroidEntryPoint
class ContactDetailActivity : BaseActivity() {

    companion object {
        private const val BUNDLE_KEY_CONTACT_ID = "BUNDLE_KEY_CONTACT_ID"
        private const val BUNDLE_KEY_CUSTOM_ID = "BUNDLE_KEY_CUSTOM_ID"
        private const val BUNDLE_KEY_CONTACT_NAME = "BUNDLE_KEY_CONTACT_NAME"
        private const val BUNDLE_KEY_CONTACT_AVATAR = "BUNDLE_KEY_CONTACT_AVATAR"
        private const val BUNDLE_KEY_CONTACT_JOINED_AT = "BUNDLE_KEY_CONTACT_JOINED_AT"

        fun startActivity(
            context: Context,
            contactID: String?,
            customID: String? = null,
            contactName: String? = null,
            sourceType: String? = null,
            source: String? = null,
            avatar: String? = null,
            joinedAt: String? = null
        ) {
            val intent = Intent(context, ContactDetailActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.contactID = contactID
            intent.customID = customID
            intent.contactName = contactName
            intent.sourceType = sourceType
            intent.source = source
            intent.avatar = avatar
            intent.joinedAt = joinedAt
            context.startActivity(intent)
        }

        private var Intent.contactID: String?
            get() = getStringExtra(BUNDLE_KEY_CONTACT_ID)
            set(value) {
                putExtra(BUNDLE_KEY_CONTACT_ID, value)
            }
        private var Intent.customID: String?
            get() = getStringExtra(BUNDLE_KEY_CUSTOM_ID)
            set(value) {
                putExtra(BUNDLE_KEY_CUSTOM_ID, value)
            }
        private var Intent.contactName: String?
            get() = getStringExtra(BUNDLE_KEY_CONTACT_NAME)
            set(value) {
                putExtra(BUNDLE_KEY_CONTACT_NAME, value)
            }
        private var Intent.avatar: String?
            get() = getStringExtra(BUNDLE_KEY_CONTACT_AVATAR)
            set(value) {
                putExtra(BUNDLE_KEY_CONTACT_AVATAR, value)
            }
        private var Intent.joinedAt: String?
            get() = getStringExtra(BUNDLE_KEY_CONTACT_JOINED_AT)
            set(value) {
                putExtra(BUNDLE_KEY_CONTACT_JOINED_AT, value)
            }
        private var Intent.sourceType: String?
            get() = getStringExtra(BUNDLE_KEY_SOURCE_TYPE)
            set(value) {
                putExtra(BUNDLE_KEY_SOURCE_TYPE, value)
            }
        private var Intent.source: String?
            get() = getStringExtra(BUNDLE_KEY_SOURCE)
            set(value) {
                putExtra(BUNDLE_KEY_SOURCE, value)
            }
    }

    private val contactId: String by lazy {
        intent.contactID ?: ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.chat_activity_contact_detail)

        if (TextUtils.isEmpty(contactId)) return

        if (savedInstanceState == null) {
            loadFragment()
        }
    }

    private fun loadFragment() {
        val fragment = ContactDetailFragment.newInstance(
            contactId = contactId,
            customId = intent.customID,
            contactName = intent.contactName,
            sourceType = intent.sourceType,
            source = intent.source,
            avatar = intent.avatar,
            joinedAt = intent.joinedAt
        )

        supportFragmentManager.commit {
            replace(R.id.fragment_container, fragment)
        }
    }
}