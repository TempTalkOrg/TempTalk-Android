package org.thoughtcrime.securesms.recipients.delegates

import org.thoughtcrime.securesms.recipients.RecipientId

interface ILiveRecipientCache {

    fun getLive(id: RecipientId): ILiveRecipient
}