package com.difft.android.websocket.api.messages

import difft.android.messageserialization.For

/** Outcome of the inbound-conversation cross-check. */
enum class ConversationVerdict { PROCESS, REJECT }

/**
 * Security invariant: an inbound message whose conversation the client resolves
 * from decrypted content must have arrived through that same conversation, as
 * stamped by an honest server into Envelope.msgExtra.conversationId.
 *
 * Three states (cross-platform-proposal.md §3):
 *   - isSyncOrSelf            -> PROCESS  (own/sync traffic is not an injection surface)
 *   - envelope absent (null)  -> PROCESS  (old/transitional server; fail-open, never drops)
 *   - present & content==env  -> PROCESS
 *   - present & content!=env  -> REJECT   (injection signature; incl. cross-shape Group<->Account)
 *
 * PRECONDITION: both conversations are already normalized through the SAME
 * representation — group ids via transformGroupIdFromServerToLocal, 1v1 via the raw
 * account number. Content side comes from SignalServiceDataClass.conversation;
 * envelope side from SignalServiceDataClass.envelopeConversation. Equality is
 * delegated to For.equals (id + typeValue), which is why cross-shape mismatch
 * rejects for free.
 *
 * @param isSyncOrSelf caller-computed exemption = isSyncMessage || senderId == myId,
 *                     resolved at the mount point. Wins over any mismatch.
 */
fun crossCheckConversation(
    contentConversation: For,
    envelopeConversation: For?,
    isSyncOrSelf: Boolean,
): ConversationVerdict {
    if (isSyncOrSelf) return ConversationVerdict.PROCESS
    if (envelopeConversation == null) return ConversationVerdict.PROCESS // absent -> fail-open
    return if (contentConversation == envelopeConversation) {
        ConversationVerdict.PROCESS
    } else {
        ConversationVerdict.REJECT // present & contradictory
    }
}
