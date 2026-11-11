package com.difft.android.chat.contacts.compose

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R

@Composable
fun CountdownAvatarTile(
    avatar: @Composable (Modifier) -> Unit,
    name: String,
    days: Int?,
    todayLabel: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
) {
    val tokens = DifftTheme.contacts
    val label = countdownDaysLabel(days, todayLabel, stringResource(R.string.contacts_days_short))
    val urgent = days != null && days <= 3
    Column(
        modifier = modifier.width(tokens.tileWidth)
            .then(if (selected) Modifier.background(DifftTheme.colors.backgroundTertiary, RoundedCornerShape(tokens.gapMedium)) else Modifier)
            .semantics { this.selected = selected }
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(tokens.tileAvatar).clip(CircleShape)) {
            avatar(Modifier.size(tokens.tileAvatar))
            if (label != null) {
                val maskHeight = tokens.tileAvatar * tokens.tileMaskFraction
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val mask = GenericShape { size, _ ->
                        val top = size.height * (1f - tokens.tileMaskFraction)
                        moveTo(0f, top)
                        lineTo(size.width, top)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    Box(Modifier.size(tokens.tileAvatar).clip(mask)) {
                        avatar(Modifier.size(tokens.tileAvatar).blur(tokens.tileBlur))
                    }
                }
                Box(Modifier.align(Alignment.BottomCenter).size(tokens.tileAvatar, maskHeight).clipToBounds()) {
                    Box(
                        Modifier.matchParentSize().background(if (urgent) tokens.countdownUrgentTint else tokens.countdownTint),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = if (urgent) tokens.countdownUrgent else tokens.countdown, color = tokens.countdownForeground)
                    }
                }
            }
        }
        Spacer(Modifier.height(tokens.gapSmall))
        Text(name, style = DifftTheme.typography.p4, color = DifftTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
