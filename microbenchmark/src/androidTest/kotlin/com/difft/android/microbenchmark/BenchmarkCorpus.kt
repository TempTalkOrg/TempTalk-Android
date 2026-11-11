package com.difft.android.microbenchmark

import org.difft.app.database.WCDB
import org.difft.app.database.test.builders.ChildRowCorpus







fun WCDB.seed(corpus: ChildRowCorpus) {
    message.insertObjects(corpus.messages)
    if (corpus.attachments.isNotEmpty()) attachment.insertObjects(corpus.attachments)
    if (corpus.mentions.isNotEmpty()) mention.insertObjects(corpus.mentions)
    if (corpus.reactions.isNotEmpty()) reaction.insertObjects(corpus.reactions)
    if (corpus.sharedContacts.isNotEmpty()) sharedContact.insertObjects(corpus.sharedContacts)
    if (corpus.sharedContactPhones.isNotEmpty()) {
        sharedContactPhone.insertObjects(corpus.sharedContactPhones)
    }
    if (corpus.translates.isNotEmpty()) translate.insertObjects(corpus.translates)
    if (corpus.speechToTexts.isNotEmpty()) speechToText.insertObjects(corpus.speechToTexts)
    if (corpus.quotes.isNotEmpty()) quote.insertObjects(corpus.quotes)
    if (corpus.forwardContexts.isNotEmpty()) forwardContext.insertObjects(corpus.forwardContexts)
    if (corpus.forwards.isNotEmpty()) forward.insertObjects(corpus.forwards)
}
