package com.difft.android.chat.contacts.contactsdetail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import com.difft.android.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

const val BUNDLE_KEY_SOURCE_TYPE = "BUNDLE_KEY_CONTACT_SOURCE_TYPE"
const val BUNDLE_KEY_SOURCE = "BUNDLE_KEY_CONTACT_SOURCE"


@AndroidEntryPoint
class ContactDetailActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(KEY_ID).orEmpty()
        if (id.isBlank()) {
            finish()
            return
        }
        setContentView(FrameLayout(this))
        
        
        if (supportFragmentManager.findFragmentByTag(ContactDetailBottomSheetDialogFragment.TAG) == null) {
            ContactDetailBottomSheetDialogFragment.show(
                this, id, intent.getStringExtra(KEY_NAME), intent.getStringExtra(BUNDLE_KEY_SOURCE_TYPE),
                intent.getStringExtra(BUNDLE_KEY_SOURCE), intent.getStringExtra(KEY_AVATAR),
                intent.getStringExtra(KEY_JOINED), customId = intent.getStringExtra(KEY_CUSTOM),
                
                
                fullConversation = true,
            )
        }
    }

    companion object {
        private const val KEY_ID = "BUNDLE_KEY_CONTACT_ID"
        private const val KEY_CUSTOM = "BUNDLE_KEY_CUSTOM_ID"
        private const val KEY_NAME = "BUNDLE_KEY_CONTACT_NAME"
        private const val KEY_AVATAR = "BUNDLE_KEY_CONTACT_AVATAR"
        private const val KEY_JOINED = "BUNDLE_KEY_CONTACT_JOINED_AT"

        fun startActivity(context: Context, contactID: String?, customID: String? = null,
            contactName: String? = null, sourceType: String? = null, source: String? = null,
            avatar: String? = null, joinedAt: String? = null,
        ) {
            
            
            val request = ContactCardRequest(contactID.orEmpty(), customID, contactName, sourceType, source, avatar, joinedAt)
            if (request.contactId.isBlank()) return
            context.startActivity(intent(context, request))
        }

        fun intent(context: Context, contactId: String, sourceType: String? = null, source: String? = null): Intent =
            intent(context, ContactCardRequest(contactId, sourceType = sourceType, source = source))

        fun intent(context: Context, request: ContactCardRequest): Intent = Intent(context, ContactDetailActivity::class.java).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(KEY_ID, request.contactId)
            putExtra(KEY_CUSTOM, request.customId)
            putExtra(KEY_NAME, request.contactName)
            putExtra(BUNDLE_KEY_SOURCE_TYPE, request.sourceType)
            putExtra(BUNDLE_KEY_SOURCE, request.source)
            putExtra(KEY_AVATAR, request.avatar)
            putExtra(KEY_JOINED, request.joinedAt)
        }
    }
}
