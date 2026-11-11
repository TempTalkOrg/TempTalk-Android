package com.difft.android.migration.v2.importer

import com.difft.android.migration.v2.model.NeutralMessage
import org.difft.app.database.models.SharedContactModel
import org.difft.app.database.models.SharedContactPhoneModel










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
