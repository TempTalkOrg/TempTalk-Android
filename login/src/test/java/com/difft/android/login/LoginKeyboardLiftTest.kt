package com.difft.android.login

import android.graphics.Insets
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.difft.android.login.databinding.ActivityBindAccountBinding
import com.difft.android.login.databinding.ActivityVerifyCodeBinding
import com.difft.android.login.viewmodel.TestScopeApplication
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue






@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [34], qualifiers = "w347dp-h509dp-xhdpi")
class LoginKeyboardLiftTest {

    private lateinit var controller: ActivityController<FragmentActivity>

    private val density get() = controller.get().resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    
    private fun mount(content: (FragmentActivity) -> ViewGroup): ViewGroup {
        controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(com.difft.android.base.R.style.Theme_Chative)
        controller.setup()
        val root = content(controller.get())
        root.setPadding(0, dp(STATUS_BAR_DP), 0, 0)
        controller.get().setContentView(root)
        idle()
        return root
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    
    private fun showKeyboard(root: ViewGroup, heightDp: Int): Int {
        val animation = WindowInsetsAnimation(WindowInsets.Type.ime(), null, 250)
        root.dispatchWindowInsetsAnimationPrepare(animation)
        val insets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, dp(heightDp)))
            .build()
        root.dispatchWindowInsetsAnimationProgress(insets, mutableListOf(animation))
        return root.rootView.height - dp(heightDp)
    }

    private fun View.windowTop(): Int = IntArray(2).also { getLocationInWindow(it) }[1]
    private fun View.windowBottom(): Int = windowTop() + height

    
    private fun ViewGroup.paddedTop(): Int = windowTop() - translationY.toInt() + paddingTop

    private fun assertAboveKeyboard(field: View, button: View, keyboardTop: Int) {
        assertTrue(field.windowBottom() <= keyboardTop, "field ends at ${field.windowBottom()}, keyboard at $keyboardTop")
        assertTrue(button.windowBottom() <= keyboardTop, "button ends at ${button.windowBottom()}, keyboard at $keyboardTop")
    }

    @After
    fun tearDown() {
        runCatching { controller.pause().stop().destroy() }
    }

    @Test
    fun `login form lifts its account field and Next above the keyboard`() {
        lateinit var loginPage: View
        val root = mount { activity ->
            FrameLayout(activity).apply {
                loginPage = LayoutInflater.from(activity).inflate(R.layout.fragment_log_in, this, false)
                addView(FrameLayout(activity).apply { addView(loginPage) })
                addView(FrameLayout(activity).apply { visibility = View.GONE })
                liftLoginFormAboveKeyboard(getChildAt(0))
            }
        }
        val field = loginPage.findViewById<View>(R.id.cl_account)
        val button = loginPage.findViewById<View>(R.id.btn_next)
        val keyboardTop = showKeyboard(root, KEYBOARD_DP)
        assertTrue(root.translationY < 0f, "the page did not move")
        assertAboveKeyboard(field, button, keyboardTop)
        assertTrue(field.windowTop() >= root.paddedTop(), "field lifted under the status bar")
    }

    @Test
    fun `sign-up shown means the login form does not lift`() {
        val root = mount { activity ->
            FrameLayout(activity).apply {
                val loginContainer = FrameLayout(activity).apply {
                    addView(LayoutInflater.from(activity).inflate(R.layout.fragment_log_in, this, false))
                    visibility = View.GONE
                }
                addView(loginContainer)
                addView(FrameLayout(activity).apply {
                    addView(LayoutInflater.from(activity).inflate(R.layout.fragment_sign_up, this, false))
                })
                liftLoginFormAboveKeyboard(loginContainer)
            }
        }
        showKeyboard(root, KEYBOARD_DP)
        idle()
        assertEquals(0f, root.translationY)
    }

    @Test
    fun `bind account form lifts its account field and Next above the keyboard`() {
        lateinit var binding: ActivityBindAccountBinding
        val root = mount { activity ->
            binding = ActivityBindAccountBinding.inflate(LayoutInflater.from(activity))
            binding.liftFormAboveKeyboard()
            binding.root
        }
        val keyboardTop = showKeyboard(root, KEYBOARD_DP)
        assertTrue(root.translationY < 0f, "the page did not move")
        assertAboveKeyboard(binding.clAccount, binding.btnNext, keyboardTop)
        assertTrue(binding.clAccount.windowTop() >= root.paddedTop(), "field lifted under the status bar")
    }

    
    
    @Test
    fun `bind account form with a tall error keeps the field visible`() {
        lateinit var binding: ActivityBindAccountBinding
        val root = mount { activity ->
            binding = ActivityBindAccountBinding.inflate(LayoutInflater.from(activity))
            binding.root.findViewById<TextView>(R.id.errorHint).apply {
                visibility = View.VISIBLE
                text = List(30) { "This account cannot be used here." }.joinToString(" ")
            }
            binding.liftFormAboveKeyboard()
            binding.root
        }
        val keyboardTop = showKeyboard(root, KEYBOARD_DP)
        assertTrue(root.translationY < 0f, "the page did not move")
        assertTrue(binding.clAccount.windowTop() >= root.paddedTop(), "field lifted under the status bar")
        assertTrue(binding.clAccount.windowBottom() <= keyboardTop, "field covered by the keyboard")
        assertTrue(binding.btnNext.windowBottom() > keyboardTop, "the span was expected to overflow")
    }

    @Test
    fun `verify code form lifts its code boxes and Next above the keyboard`() {
        lateinit var binding: ActivityVerifyCodeBinding
        val root = mount { activity ->
            binding = ActivityVerifyCodeBinding.inflate(LayoutInflater.from(activity))
            binding.liftCodeAboveKeyboard()
            binding.root
        }
        val keyboardTop = showKeyboard(root, KEYBOARD_DP)
        assertTrue(root.translationY < 0f, "the page did not move")
        assertAboveKeyboard(binding.emailCodeView, binding.btnNext, keyboardTop)
        assertTrue(binding.inputZone.windowTop() >= root.paddedTop(), "code boxes lifted under the status bar")
    }

    private companion object {
        const val STATUS_BAR_DP = 24
        const val KEYBOARD_DP = 260
    }
}
