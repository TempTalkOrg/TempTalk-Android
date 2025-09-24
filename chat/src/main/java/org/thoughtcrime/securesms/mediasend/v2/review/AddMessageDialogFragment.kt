package org.thoughtcrime.securesms.mediasend.v2.review

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import com.difft.android.chat.R
import com.difft.android.chat.databinding.V2MediaAddMessageDialogFragmentBinding
import org.thoughtcrime.securesms.components.KeyboardEntryDialogFragment
import org.thoughtcrime.securesms.components.ViewBinderDelegate
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionViewModel
import org.thoughtcrime.securesms.util.ViewUtil

class AddMessageDialogFragment : KeyboardEntryDialogFragment(R.layout.v2_media_add_message_dialog_fragment) {

    private val viewModel: MediaSelectionViewModel by viewModels(
        ownerProducer = { requireActivity() }
    )

    private val binding by ViewBinderDelegate(V2MediaAddMessageDialogFragmentBinding::bind)


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        binding.content.addAMessageInput.addTextChangedListener(afterTextChanged = {
            viewModel.setMessage(it)
        })

        binding.content.addAMessageInput.setText(requireArguments().getCharSequence(ARG_INITIAL_TEXT))

        binding.hud.setOnClickListener { dismissAllowingStateLoss() }

        val confirm: View = view.findViewById(R.id.confirm_button)
        confirm.setOnClickListener { dismissAllowingStateLoss() }
    }

    override fun onResume() {
        super.onResume()

        ViewUtil.focusAndShowKeyboard(binding.content.addAMessageInput)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (isResumed) {
            viewModel.setMessage(binding.content.addAMessageInput.text)
        }
    }

    companion object {

        const val TAG = "ADD_MESSAGE_DIALOG_FRAGMENT"

        private const val ARG_INITIAL_TEXT = "arg.initial.text"
        private const val ARG_INITIAL_EMOJI_TOGGLE = "arg.initial.emojiToggle"

        fun show(fragmentManager: FragmentManager, initialText: CharSequence?, startWithEmojiKeyboard: Boolean) {
            AddMessageDialogFragment().apply {
                arguments = Bundle().apply {
                    putCharSequence(ARG_INITIAL_TEXT, initialText)
                    putBoolean(ARG_INITIAL_EMOJI_TOGGLE, startWithEmojiKeyboard)
                }
            }.show(fragmentManager, TAG)
        }
    }
}
