package com.difft.android.chat.mediasend.v2.review

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import com.difft.android.base.utils.utf8Substring
import com.difft.android.base.widget.applyFloatingSurface
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.ui.setConfidentialInputBorder
import com.difft.android.chat.common.MAX_TEXT_FILE_SIZE
import com.difft.android.chat.databinding.V2MediaAddMessageDialogFragmentBinding
import com.difft.android.chat.components.KeyboardEntryDialogFragment
import com.difft.android.chat.components.ViewBinderDelegate
import com.difft.android.chat.mediasend.v2.MediaSelectionViewModel

class AddMessageDialogFragment : KeyboardEntryDialogFragment(R.layout.v2_media_add_message_dialog_fragment) {

    private val viewModel: MediaSelectionViewModel by viewModels(
        ownerProducer = { requireActivity() }
    )

    private val binding by ViewBinderDelegate(com.difft.android.chat.databinding.V2MediaAddMessageDialogFragmentBinding::bind)


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        super.onCreateView(MediaReviewInputChrome.darkInflater(inflater), container, savedInstanceState)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        val input = binding.content.addAMessageInput
        input.onQueryChanged = ::onMessageEdited
        input.onClear = { viewModel.setMessage("") }
        
        
        if (savedInstanceState == null) {
            onMessageEdited(requireArguments().getCharSequence(ARG_INITIAL_TEXT)?.toString().orEmpty())
        }

        binding.hud.setOnClickListener { dismissAllowingStateLoss() }

        val confirm: View = view.findViewById(R.id.confirm_button)
        confirm.setOnClickListener { dismissAllowingStateLoss() }

        applyConfidentialStyle()
    }

    
    private fun onMessageEdited(text: String) {
        val capped = if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_FILE_SIZE) {
            ToastUtil.show(getString(R.string.text_file_exceeds_10mb_limit))
            text.utf8Substring(MAX_TEXT_FILE_SIZE)
        } else {
            text
        }
        binding.content.addAMessageInput.query = capped
        viewModel.setMessage(capped)
    }

    private fun applyConfidentialStyle() {
        val isConfidential = viewModel.state.value?.confidentialMode == 1
        val input = binding.content.addAMessageInput
        
        (input.parent as View).setBackgroundColor(ContextCompat.getColor(input.context, com.difft.android.base.R.color.bg1))
        input.applyFloatingSurface(cornerRadiusPx = resources.getDimension(R.dimen.chat_input_field_radius))
        input.setConfidentialInputBorder(isConfidential, resources.getDimension(R.dimen.chat_input_field_radius))
        input.containerColor = android.graphics.Color.TRANSPARENT
    }

    override fun onResume() {
        super.onResume()

        binding.content.addAMessageInput.focusAndShowKeyboard()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (isResumed) {
            viewModel.setMessage(binding.content.addAMessageInput.query)
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
