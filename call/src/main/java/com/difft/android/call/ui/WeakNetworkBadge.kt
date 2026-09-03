package com.difft.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.R
import com.difft.android.call.core.CallUiController
import com.difft.android.call.network.resolveBadge

/** Figma 17277:1367 — a 20dp badge box whose 14dp icon frame sits at (3,3), i.e. centred. */
private val BADGE_BOX = 20.dp
private val BADGE_ICON = 14.dp
private val BADGE_CORNER = 4.dp

/**
 * Weak-network badge for one participant tile. Renders only the BAD tier (the design draws no
 * good/excellent state); the verdict, both suppression rules, the local-tile exclusion and the
 * two-person hand-off to the top banner are all applied in [resolveBadge] — this composable never
 * re-derives them.
 *
 * Collects [CallUiController.networkQuality] HERE rather than in MultiParticipantItem: a collector in
 * the tile's own scope would recompose the whole cell (ConstraintLayout + the AndroidView video
 * renderer) on every quality change, for every participant. Same rationale as the zero-recomposition
 * chain documented in PortraitParticipantLayout.
 *
 * @param localIdentity the local user's uid (`globalServices.myId`) — the SAME string the local tile
 *   passes as [identity]. Never the SDK identity: the local tile's uid is the bare uid, so a suffixed
 *   identity would make the equality probe miss and badge the local tile after all.
 * @param identity this tile's uid — bare uid for the local tile, `identity.value` for a remote.
 * @param participantCount local + the remotes currently in the room; see [resolveBadge].
 */
@Composable
internal fun ParticipantWeakNetworkBadge(
    controller: CallUiController,
    localIdentity: String,
    identity: String,
    participantCount: Int,
    modifier: Modifier = Modifier,
) {
    val view by controller.networkQuality.collectAsState()
    if (!resolveBadge(view, localIdentity, participantCount, identity)) return

    Box(
        modifier = modifier
            .testTag("call_weak_network_badge")
            .size(BADGE_BOX)
            .background(DifftTheme.colors.backgroundElevate, RoundedCornerShape(BADGE_CORNER)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.call_ic_wifi_x),
            // The glyph carries no text, so screen readers get the peer-side copy the banner would
            // have spoken in a two-person call.
            contentDescription = stringResource(id = R.string.call_other_network_poor_tip),
            // Literal white, not textPrimary: the design fills this glyph pure #FFFFFF while
            // textPrimary is #EAECEF. Same convention (and same forced-dark subtree) as
            // MainPageWithTopStatusView's literal Color.White icons.
            tint = Color.White,
            modifier = Modifier.size(BADGE_ICON),
        )
    }
}
