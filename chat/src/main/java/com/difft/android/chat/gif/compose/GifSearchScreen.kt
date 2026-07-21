package com.difft.android.chat.gif.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.gif.GifPanelContract
import com.difft.android.chat.gif.GifPanelViewModel

/**
 * Full-screen GIF search content: a search box above the shared result grid.
 *
 * The search box is styled to match the project's canonical XML search boxes
 * (search_input_bg_search_input: radius 8, solid bg2, padding 12h/8v) so it looks consistent with
 * the rest of the app (Issue 3): backgroundSecondary (bg2, NOT bg3), an accent-colored caret
 * (cursorBrush = primary), and the standard magnifier/clear drawables.
 *
 * Two right-side controls: a clear-x INSIDE the box (clears the query -> trending) and a close-X
 * OUTSIDE to its right ([onClose] dismisses the sheet). Reuses [GifPanelViewModel]'s Search intent
 * (debounce lives in the VM) and [GifGrid]. Hosted in
 * [com.difft.android.chat.gif.GifSearchDialogFragment].
 */
@Composable
fun GifSearchScreen(
    viewModel: GifPanelViewModel,
    onPick: (com.difft.android.chat.gif.GifUiItem) -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DifftTheme.colors.background)
            // Bridge the inner LazyGrid's scroll to the host BottomSheet (View) so the list scrolls
            // first and the sheet only drags-to-dismiss once the list is at the top — otherwise the
            // BottomSheetBehavior swallows the downward drag and closes the sheet (Issue: gesture
            // conflict). Mirrors how the RecyclerView-based forward dialog cooperates natively.
            .nestedScroll(rememberNestedScrollInteropConnection())
    ) {
        // Top row: [ search input box (weight 1) ] [ close-X ]. The close-X dismisses the whole
        // sheet (mirrors ChatSelectBottomSheetFragment's tv_close); the clear-x inside the box only
        // clears the query. (Issue 3)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DifftTheme.colors.backgroundSecondary)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.search_input_ic_search_input),
                    contentDescription = null,
                    tint = DifftTheme.colors.icon,
                    modifier = Modifier.size(20.dp)
                )
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (state.query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.gif_search_hint),
                            style = DifftTheme.typography.bodyMedium,
                            color = DifftTheme.colors.textDisabled
                        )
                    }
                    BasicTextField(
                        value = state.query,
                        onValueChange = { viewModel.dispatch(GifPanelContract.Intent.Search(it)) },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = DifftTheme.colors.textPrimary,
                            fontSize = DifftTheme.typography.bodyMedium.fontSize
                        ),
                        // Accent caret to match the XML EditText theme cursor (round-1 left the default).
                        cursorBrush = SolidColor(DifftTheme.colors.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Trailing clear "x" INSIDE the box: visible only with a non-empty query; clearing
                // returns to trending (Search("") -> reload(null) in the VM). (Issue 3b)
                if (state.query.isNotEmpty()) {
                    Icon(
                        painter = painterResource(R.drawable.search_input_ic_search_clear),
                        contentDescription = stringResource(R.string.gif_search_clear),
                        tint = DifftTheme.colors.textSecondary,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { viewModel.dispatch(GifPanelContract.Intent.Search("")) }
                    )
                }
            }
            // Standalone close-X OUTSIDE the box: dismisses the whole search sheet, the primary
            // close affordance (mirrors ChatSelectBottomSheetFragment's tv_close). (Issue 3b)
            Icon(
                painter = painterResource(R.drawable.chat_icon_close),
                contentDescription = stringResource(R.string.chat_select_dialog_close),
                tint = DifftTheme.colors.icon,
                // chat_icon_close is a 14x14 vector whose X fills the viewport edge-to-edge (no
                // internal whitespace), so it looks oversized when scaled up. Render it at its native
                // 14dp inside a 28dp tap target (7dp padding) so the glyph isn't bold/flush.
                modifier = Modifier
                    .size(28.dp)
                    .clickable { onClose() }
                    .padding(7.dp)
            )
        }

        GifGrid(
            items = state.items,
            // Empty-state label only for a real (non-empty) search miss; while trending loads with
            // an empty query, the spinner shows instead of "no result" (Issue 4a).
            emptyResult = state.emptyResult && state.query.isNotEmpty(),
            onPick = onPick,
            onLoadNextPage = { viewModel.dispatch(GifPanelContract.Intent.LoadNextPage) },
            isLoading = state.isLoading,
            loadError = state.loadError,
            onAddToFavorite = { item -> viewModel.dispatch(GifPanelContract.Intent.FavoriteGif(item)) },
            // Top = 0: the 8dp gap to the search box comes solely from the search row's bottom padding
            // (the grid's default 16dp top would otherwise stack with it to 24dp). Sides/bottom keep 16.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        )
    }
}
