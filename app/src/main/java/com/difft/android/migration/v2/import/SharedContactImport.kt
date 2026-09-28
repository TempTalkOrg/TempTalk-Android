package com.difft.android.migration.v2.importer

import com.difft.android.migration.v2.model.NeutralMessage
import org.difft.app.database.models.SharedContactModel
import org.difft.app.database.models.SharedContactPhoneModel

/**
 * Re-explode a message's contact-card content into the `sharedContact` and `sharedContactPhone`
 * tables on import — the inverse of
 * [com.difft.android.migration.v2.export.NeutralProjections.sharedContacts].
 *
 * The [SharedContactModel] is inserted first, keyed by the message's stableId, and its freshly
 * assigned target-local PK becomes each phone row's `sharedContactDatabaseId` — a local FK remapped
 * here like the quote and forward FKs. Applies to normal and confidential cards alike.
 */
internal fun WcdbWriteTarget.insertSharedContactsOf(n: NeutralMessage) {
    n.sharedContacts.forEach { sc ->
        val scPk = insertSharedContact(SharedContactModel().apply {
            messageId = n.stableId
            givenName = sc.givenName
            familyName = sc.familyName
            namePrefix = sc.namePrefix
            nameSuffix = sc.nameSuffix
            middleName = sc.middleName
            displayName = sc.displayName
        })
        sc.phones.forEach { ph ->
            insertSharedContactPhone(SharedContactPhoneModel().apply {
                sharedContactDatabaseId = scPk
                phoneNumber = ph.value
                phoneNumberType = ph.type
                phoneNumberLabel = ph.label
            })
        }
    }
}
