package com.difft.android.chat.contacts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.ui.sidechrome.RailAction
import com.difft.android.base.ui.sidechrome.SideChrome
import com.difft.android.base.ui.sidechrome.SideChromeModel
import com.difft.android.base.ui.sidechrome.onSideChromeActive
import com.difft.android.base.ui.sidechrome.padForSideChrome
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.topchrome.TopChromeOwner
import com.difft.android.base.ui.topchrome.TopChromeStyle
import com.difft.android.base.utils.DualPaneHost
import com.difft.android.base.utils.DualPaneUtils.isInDualPaneMode
import com.difft.android.chat.R
import com.difft.android.chat.contacts.compose.ContactsScreen
import com.difft.android.chat.contacts.compose.contactsChromeVisibility
import com.difft.android.chat.contacts.compose.contactsTitleCanCollapse
import com.difft.android.chat.contacts.contactsdetail.ContactCardRequest
import com.difft.android.chat.contacts.contactsdetail.ContactCardHost
import com.difft.android.chat.contacts.contactsdetail.showContactCard
import com.difft.android.chat.contacts.groupcard.GroupCardNavigationCallback
import com.difft.android.chat.contacts.groupcard.showGroupCard
import com.difft.android.chat.contacts.mvi.ContactsContract.Effect
import com.difft.android.chat.contacts.mvi.ContactsContract.Intent
import com.difft.android.chat.contacts.mvi.ContactsViewModel
import com.difft.android.chat.invite.InviteCodeActivity
import com.difft.android.chat.recent.ConversationNavigationCallback
import com.difft.android.chat.recent.DualPaneSelectionListener
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch


@AndroidEntryPoint
class ContactsFragment : Fragment(), DualPaneSelectionListener, TopChromeOwner {
    override val topChromeStyle get() = TopChromeStyle.Band
    private val model: ContactsViewModel by viewModels()
    private var glass: ContactsGlassBackdrop? = null
    private var railActive by mutableStateOf(false)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        glass = ContactsGlassBackdrop(view as ViewGroup, viewLifecycleOwner)
        val dualPane = isInDualPaneMode()
        if (!dualPane) {
            padForSideChrome(view)
            SideChrome.of(requireContext())?.registry?.publish(viewLifecycleOwner, SideChromeModel(actions = listOf(
                RailAction("add_contact", R.drawable.chat_tabler_add_contact, getString(R.string.contact_add_contacts),
                    onClick = { model.dispatch(Intent.AddContact) }),
            )), base = true)
            onSideChromeActive { railActive = it }
        }
        val host = activity as? DualPaneHost
        val navigation = activity as? ConversationNavigationCallback
        (view as ComposeView).setContent {
            DifftTheme(applyWindowBackground = false) {
                val state by model.state.collectAsStateWithLifecycle()
                val configuration = LocalConfiguration.current
                val density = LocalDensity.current
                val bottomPx = host?.homeTabBarObstructionPx?.collectAsStateWithLifecycle()?.value ?: 0
                val bottom = with(density) { maxOf(bottomPx, WindowInsets.navigationBars.getBottom(this)).toDp() }
                val (band, controls) = contactsChromeVisibility(activity?.lifecycle, navigation?.detailCoversList)
                ContactsScreen(
                    state, model::dispatch,
                    collapseTitle = contactsTitleCanCollapse(configuration.screenHeightDp, requireActivity().isInMultiWindowMode),
                    showAddInHeader = dualPane || !railActive,
                    bandVisible = band, controlsVisible = controls, bottomObstruction = bottom,
                    onGlassSource = { glass?.setSource(it) },
                )
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.effect.collect { effect ->
                    when (effect) {
                        
                        is Effect.OpenContact -> ContactCardRequest(effect.id).let { request ->
                            if ((activity as? ContactCardHost)?.onContactCardSelected(request) != true) {
                                showContactCard(requireContext(), request)
                            }
                        }
                        is Effect.OpenGroup ->
                            if ((activity as? GroupCardNavigationCallback)?.onGroupCardSelected(effect.id) != true) {
                                showGroupCard(requireContext(), effect.id)
                            }
                        Effect.AddContact -> InviteCodeActivity.startActivity(requireActivity())
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!model.state.value.loading) model.dispatch(Intent.Refresh)
        val navigation = activity as? ConversationNavigationCallback
        updateDualPaneSelection(navigation?.currentSelectedConversationId.takeIf { navigation?.isDualPaneMode == true })
    }

    override fun updateDualPaneSelection(selectedId: String?) {
        if (isAdded) model.dispatch(Intent.SelectId(selectedId))
    }

    override fun onDestroyView() {
        glass?.close()
        glass = null
        super.onDestroyView()
    }
}
