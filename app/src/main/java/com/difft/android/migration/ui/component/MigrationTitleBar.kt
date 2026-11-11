package com.difft.android.migration.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.compose.FloatingIconButton
import com.difft.android.base.ui.compose.FloatingIconButtonEdge
import com.difft.android.base.ui.compose.FloatingIconButtonTitleGap
import com.difft.android.base.ui.theme.DifftTheme


enum class MigrationNavIcon { BACK, CLOSE, NONE }






@Composable
fun MigrationTitleBar(
    title: String?,
    navIcon: MigrationNavIcon,
    onNav: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TitleBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (navIcon) {
            MigrationNavIcon.NONE -> Spacer(Modifier.width(16.dp))
            MigrationNavIcon.BACK, MigrationNavIcon.CLOSE -> FloatingIconButton(
                iconRes = if (navIcon == MigrationNavIcon.BACK) {
                    com.difft.android.base.R.drawable.chative_ic_back
                } else {
                    R.drawable.migration_ic_close
                },
                contentDescription = null,
                onClick = onNav,
                modifier = Modifier.padding(start = FloatingIconButtonEdge, end = FloatingIconButtonTitleGap),
            )
        }
        if (title != null) {
            Text(
                text = title,
                style = DifftTheme.typography.h4,
                color = DifftTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val TitleBarHeight = 52.dp
