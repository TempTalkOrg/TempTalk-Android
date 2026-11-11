package com.difft.android.chat.contacts.data

import com.difft.android.base.widget.sideBar.CharacterParser
import com.difft.android.chat.contacts.contactsall.toPinyinSortKey
import com.difft.android.chat.contacts.mvi.DirectorySection
import java.text.Normalizer
import java.util.Locale


internal fun directoryLetter(name: String): String {
    if (name.isEmpty()) return "#"
    val first = name.codePointAt(0)
    val initial = when (Character.UnicodeScript.of(first)) {
        Character.UnicodeScript.LATIN -> Normalizer.normalize(
            String(Character.toChars(first)), Normalizer.Form.NFD,
        ).uppercase(Locale.ROOT).firstOrNull()
        Character.UnicodeScript.HAN -> CharacterParser.getSelling(String(Character.toChars(first)))
            .firstOrNull()?.uppercaseChar()
        else -> null
    }
    return initial?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"
}


internal fun <T> directorySections(rows: List<T>, name: (T) -> String): List<DirectorySection<T>> =
    rows.groupBy { directoryLetter(name(it)) }
        .toSortedMap(compareBy<String> { it == "#" }.thenBy { it })
        .map { (letter, bucket) ->
            DirectorySection(letter, bucket.sortedWith(compareBy { name(it).toPinyinSortKey() }))
        }
