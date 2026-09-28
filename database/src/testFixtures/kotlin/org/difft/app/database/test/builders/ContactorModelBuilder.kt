package org.difft.app.database.test.builders

import org.difft.app.database.models.ContactorModel

/**
 * A minimally populated [ContactorModel]: the id/name pair is all the avatar and contact-cache
 * paths read, and leaving every other column at its default keeps `equals` (15-field value
 * equality) predictable for tests that compare two separately built instances.
 */
fun buildContactor(id: String, name: String? = null): ContactorModel = ContactorModel().apply {
    this.id = id
    this.name = name
}
