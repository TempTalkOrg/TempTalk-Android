package com.difft.android.setting.language

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.R
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.LanguageData
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.databinding.ActivityLanguageBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlin.system.exitProcess

/**
 * Fragment for language settings
 * Can be displayed in both Activity (single-pane) and dual-pane mode
 */
@AndroidEntryPoint
class LanguageFragment : Fragment() {

    companion object {
        fun newInstance() = LanguageFragment()
    }

    private var _binding: ActivityLanguageBinding? = null
    private val binding get() = _binding!!

    private val adapter: LanguageAdapter by lazy {
        object : LanguageAdapter() {
            override fun onItemClicked(languageData: LanguageData, position: Int) {
                val context = requireContext()
                if (languageData.locale == LanguageUtils.getLanguage(context)) return

                val title = ResUtils.getLocaleStringResource(context, languageData.locale, R.string.language_restart_required)
                val content = ResUtils.getLocaleStringResource(context, languageData.locale, R.string.language_restart_tips)
                val ok = ResUtils.getLocaleStringResource(context, languageData.locale, R.string.language_restart)
                val cancel = ResUtils.getLocaleStringResource(context, languageData.locale, R.string.language_restart_later)

                ComposeDialogManager.showMessageDialog(
                    context = context,
                    title = title,
                    message = content,
                    confirmText = ok,
                    cancelText = cancel,
                    onConfirm = {
                        LanguageUtils.saveLanguage(context, languageData.locale)

                        context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            startActivity(this)
                        }
                        android.os.Process.killProcess(android.os.Process.myPid())
                        exitProcess(0)
                    },
                    onCancel = {
                        LanguageUtils.locale = LanguageUtils.getLanguage(context)
                        LanguageUtils.saveLanguage(context, languageData.locale)
                        adapter.submitList(LanguageUtils.getLanguageList(context))
                    }
                )
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityLanguageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupBackButton(binding.ibBack)
        initView()
    }

    private fun initView() {
        binding.rvLanguage.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@LanguageFragment.adapter
            itemAnimator = null
        }

        adapter.submitList(LanguageUtils.getLanguageList(requireContext()))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

