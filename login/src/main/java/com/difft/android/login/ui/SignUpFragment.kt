package com.difft.android.login.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.difft.android.base.widget.DifftClearableInputView
import com.difft.android.login.R
import com.difft.android.login.intro.RegisterIntroActivity
import com.difft.android.login.databinding.FragmentSignUpBinding
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SignUpFragment : Fragment() {

    companion object {
        fun newInstance() = SignUpFragment()
    }

    private val mBinding: FragmentSignUpBinding by viewbind()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return mBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mBinding.handleZone.setOnClickListener {
            RegisterIntroActivity.startActivity(requireActivity())
        }

        mBinding.tvLogIn.setOnClickListener {
            val fragmentLogIn = requireActivity().findViewById<View>(R.id.fragment_log_in)
            val fragmentSignUp = requireActivity().findViewById<View>(R.id.fragment_sign_up)

            fragmentLogIn.visibility = View.VISIBLE
            fragmentSignUp.visibility = View.GONE

            // The account field is a Compose-backed component; a plain View-level focus request
            // cannot reach the inner text field.
            fragmentLogIn.findViewById<DifftClearableInputView>(R.id.account).focusAndShowKeyboard()
        }
    }
}