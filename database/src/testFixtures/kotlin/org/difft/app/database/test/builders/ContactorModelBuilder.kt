package org.difft.app.database.test.builders

import org.difft.app.database.models.ContactorModel






fun buildContactor(id: String, name: String? = null): ContactorModel = ContactorModel().apply {
    this.id = id
    this.name = name
}
