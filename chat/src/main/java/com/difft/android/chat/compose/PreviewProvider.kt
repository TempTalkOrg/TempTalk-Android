package com.difft.android.chat.compose

import androidx.compose.ui.tooling.preview.PreviewParameterProvider

internal class StateSelectMessageModelDataProvider :
    PreviewParameterProvider<SelectMessageState> {
    override val values: Sequence<SelectMessageState> = sequenceOf(
        
        SelectMessageState(
            editModel = true,
            selectedMessageIds = setOf("1", "2", "3"),
            totalMessageCount = 10,
            recallableMessageIds = setOf("1", "2")  
        ),
        
        SelectMessageState(
            editModel = true,
            selectedMessageIds = setOf("1"),
            totalMessageCount = 3,
            recallableMessageIds = emptySet()  
        ),
        
        SelectMessageState(
            editModel = true,
            selectedMessageIds = setOf("1", "2", "3"),
            totalMessageCount = 5,
            recallableMessageIds = setOf("1", "2", "3")  
        )
    )
}
