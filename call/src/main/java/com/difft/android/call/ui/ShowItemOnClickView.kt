package com.difft.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.theme.DifftTheme


@Composable
fun ShowItemOnClickView(config: List<String>, expanded: Boolean, setExpanded:(Boolean) ->Unit, onClickItem: (Int) -> Unit){

    DropdownMenu(
        modifier = Modifier
            .background(DifftTheme.colors.backgroundPopup),
        expanded = expanded,
        onDismissRequest = { setExpanded(false) },
    ) {
        config.forEachIndexed { index, item ->
            DropdownMenuItem(
                modifier = Modifier
                    .size(width = 100.dp, height = 30.dp)
                    .background(
                        DifftTheme.colors.backgroundPopup
                    ),
                text = {
                    Text(text = item, fontSize = 12.sp, color = Color.White)
                },
                onClick = {
                    onClickItem(index)
                },
            )
        }
    }
}