package com.difft.android.migration.v2.model

import org.difft.app.database.models.MessageModel

/**
 * Sole owner of deriving the canonical [ContentKind] of an Android [MessageModel]. Android infers a
 * message's kind from field presence rather than a stored column, and every caller must derive it the
 * same way: applying [fkOrNull] uniformly keeps the label identical whether the row came from the
 * real DB (a boxed null FK) or from a round-tripped staging DB (0).
 *
 * Priority order — do not reorder without auditing the importer's inverse mapping. FORWARD is checked
 * before the attachment discriminator so a combined forward whose row also carries `TYPE_ATTACHMENT`
 * still classifies as FORWARD on both the export and re-projection sides.
 */
fun deriveContentKind(m: MessageModel): ContentKind = when {
    m.type == MessageModel.TYPE_NOTIFY -> ContentKind.SYSTEM
    m.forwardContextDatabaseId.fkOrNull() != null -> ContentKind.FORWARD
    m.type == MessageModel.TYPE_ATTACHMENT -> ContentKind.FILE
    else -> ContentKind.TEXT
}

/**
 * Treats a WCDB-hydrated nullable-Long FK as absent when it is null or 0. WCDB's `getLong()` coerces
 * SQL NULL to 0 on read-back, so a re-read row never carries a Java-null FK; matching SQL `.isNull`
 * semantics here keeps the derivation identical regardless of read source.
 */
fun Long?.fkOrNull(): Long? = this?.takeIf { it != 0L }
