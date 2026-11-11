package com.difft.android.chat.focus.invalid

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R





@Composable
fun FocusStatusStrip(
    controller: FocusInvalidController,
    onOpenSettings: () -> Unit,
) {
    val visible by controller.stripVisible.collectAsStateWithLifecycle()
    if (!visible || !controller.isSignedIn) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DifftTheme.colors.warning.copy(alpha = 0.16f))
            .clickable(onClick = onOpenSettings)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(com.difft.android.base.R.drawable.migration_ic_alert_triangle),
            contentDescription = null,
            tint = DifftTheme.colors.textWarning,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.focus_strip_title),
            style = DifftTheme.typography.h6,
            color = DifftTheme.colors.textPrimary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.focus_strip_subtitle),
            style = DifftTheme.typography.p3,
            color = DifftTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.focus_strip_action),
            style = DifftTheme.typography.p3,
            color = DifftTheme.colors.primary,
            modifier = Modifier.padding(start = 12.dp),
        )
        Icon(
            painter = painterResource(com.difft.android.base.R.drawable.base_ic_close),
            contentDescription = stringResource(R.string.focus_strip_close),
            tint = DifftTheme.colors.icon,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(20.dp)
                .clickable { controller.dismissStrip(controller.currentEpisode()) },
        )
    }
}


fun ComposeView.bindFocusStatusStrip(controller: FocusInvalidController, onOpenSettings: () -> Unit) {
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    setContent { DifftTheme { FocusStatusStrip(controller, onOpenSettings) } }
}
