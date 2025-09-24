package org.thoughtcrime.securesms.recipients

import android.content.Context
import com.difft.android.websocket.api.push.ServiceId
import java.security.Policy
import java.util.Optional

class Recipient(
    val id: RecipientId,
    private val accountId: String? = null,
    private val serviceIdValue: ServiceId? = null
) {
    val e164: Optional<String>
        get() = if (accountId.isNullOrEmpty()) Optional.empty() else Optional.of(accountId)
    val serviceId: Optional<ServiceId>
        get() = if (serviceIdValue == null) Optional.empty() else Optional.of(serviceIdValue)

    companion object {
        @JvmStatic
        fun externalPush(serviceId: ServiceId?, e164: String?): Recipient {
            // TODO RecipientId 从数据库获取
            return Recipient(RecipientId.UNKNOWN, e164, serviceId)
        }

        @JvmStatic
        fun live(recipientId: RecipientId): Policy {
            TODO("Not yet implemented")
        }

        @JvmStatic
        fun external(context: Context, address: String): Recipient {
            TODO("Not yet implemented")
        }
    }
}