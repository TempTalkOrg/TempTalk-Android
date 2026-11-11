package org.difft.app.database.hydration

import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.models.ForwardContextModel
import org.difft.app.database.models.ForwardModel
import org.difft.app.database.models.MentionModel
import org.difft.app.database.models.QuoteModel
import org.difft.app.database.models.ReactionModel
import org.difft.app.database.models.SharedContactModel
import org.difft.app.database.models.SharedContactPhoneModel
import org.difft.app.database.models.SpeechToTextModel
import org.difft.app.database.models.TranslateModel
















interface MessageChildRowLoader {

    

    fun attachmentsByMessageId(ids: List<String>): List<AttachmentModel>

    fun mentionsByMessageId(ids: List<String>): List<MentionModel>

    fun reactionsByMessageId(ids: List<String>): List<ReactionModel>

    fun sharedContactsByMessageId(ids: List<String>): List<SharedContactModel>

    fun translatesByMessageId(ids: List<String>): List<TranslateModel>

    fun speechToTextsByMessageId(ids: List<String>): List<SpeechToTextModel>

    

    fun quotesByDatabaseId(quoteIds: List<Long>): List<QuoteModel>

    fun forwardContextsByDatabaseId(fcIds: List<Long>): List<ForwardContextModel>

    

    fun attachmentsByQuoteId(quoteIds: List<Long>): List<AttachmentModel>

    fun phonesBySharedContactId(scIds: List<Long>): List<SharedContactPhoneModel>

    
    fun topLevelForwardsByContextId(fcIds: List<Long>): List<ForwardModel>

    
    fun forwardsByParentId(parentIds: List<Long>): List<ForwardModel>

    fun attachmentsByForwardId(forwardIds: List<Long>): List<AttachmentModel>

    fun mentionsByForwardId(forwardIds: List<Long>): List<MentionModel>
}
