package org.thoughtcrime.securesms.recipients.delegates

import org.thoughtcrime.securesms.recipients.Recipient

interface ILiveRecipient {
    fun get(): Recipient
}