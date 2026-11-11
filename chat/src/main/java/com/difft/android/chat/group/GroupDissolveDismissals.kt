package com.difft.android.chat.group

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update


@Singleton
class GroupDissolveDismissals @Inject constructor() {
    private val _groupIds = MutableStateFlow<Set<String>>(emptySet())
    val groupIds = _groupIds.asStateFlow()

    fun dismiss(groupId: String) {
        if (groupId.isNotBlank()) _groupIds.update { it + groupId }
    }
}
