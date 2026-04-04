package com.difft.android.setting.theme

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.databinding.ActivityThemeBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Fragment for theme settings
 * Can be displayed in both Activity (single-pane) and dual-pane mode
 */
@AndroidEntryPoint
class ThemeFragment : Fragment() {

    companion object {
        fun newInstance() = ThemeFragment()
    }

    private var _binding: ActivityThemeBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var userManager: UserManager

    private val adapter: ThemeAdapter by lazy {
        object : ThemeAdapter() {
            override fun onItemClicked(themeData: ThemeData, position: Int) {
                when (themeData.theme) {
                    AppCompatDelegate.MODE_NIGHT_YES -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                        userManager.update {
                            this.theme = AppCompatDelegate.MODE_NIGHT_YES
                        }
                    }

                    AppCompatDelegate.MODE_NIGHT_NO -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                        userManager.update {
                            this.theme = AppCompatDelegate.MODE_NIGHT_NO
                        }
                    }

                    else -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                        userManager.update {
                            this.theme = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        }
                    }
                }
                updateSelectedTheme(themeData.theme)
            }
        }
    }

    private lateinit var themeDataList: List<ThemeData>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityThemeBinding.inflate(inflater, container, false)
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
            adapter = this@ThemeFragment.adapter
            itemAnimator = null
        }

        themeDataList = mutableListOf<ThemeData>().apply {
            add(ThemeData(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, getString(com.difft.android.chat.R.string.me_theme_system), false))
            add(ThemeData(AppCompatDelegate.MODE_NIGHT_NO, getString(com.difft.android.chat.R.string.me_theme_light), false))
            add(ThemeData(AppCompatDelegate.MODE_NIGHT_YES, getString(com.difft.android.chat.R.string.me_theme_dark), false))
        }

        val currentTheme = userManager.getUserData()?.theme ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        updateSelectedTheme(currentTheme)
    }

    private fun updateSelectedTheme(selectedTheme: Int) {
        themeDataList.forEach { it.selected = false }
        themeDataList.find { it.theme == selectedTheme }?.selected = true
        adapter.submitList(themeDataList.toList())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

