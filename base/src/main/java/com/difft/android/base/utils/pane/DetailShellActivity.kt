package com.difft.android.base.utils.pane

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.difft.android.base.BaseActivity
import com.difft.android.base.R
import com.difft.android.base.log.lumberjack.L
import java.util.UUID


interface DetailShellHost {
    fun launchPush(intent: Intent): Boolean
}


abstract class DetailShellActivity : BaseActivity(), DetailShellHost {
    private var pendingChild: DetailShellPop.Request? = null
    internal var inheritedShellRequest: DetailShellPop.Request? = null
        private set

    private var childLauncher: ActivityResultLauncher<Intent>? = null
    private val resultCleanup = Handler(Looper.getMainLooper())

    override fun launchPush(intent: Intent): Boolean {
        if (pendingChild != null || isFinishing || isDestroyed) return false
        
        
        if (!isProtocolDestination(intent)) {
            startActivity(Intent(intent).apply { removeExtra(DetailShellPop.EXTRA_REQUEST_ID) })
            return true
        }
        val request = DetailShellPop.Request(UUID.randomUUID().toString(), intent.getStringExtra(DetailShellPop.EXTRA_SUBJECT_ID))
        pendingChild = request
        return try {
            val launcher = registerChild(request)
            if (pendingChild != request) return false
            launcher.launch(request.writeTo(Intent(intent)))
            true
        } catch (e: RuntimeException) {
            releaseChild(request)
            L.w { "[DetailShell] event=child_launch_failed ${e.stackTraceToString()}" }
            false
        }
    }

    private fun isProtocolDestination(intent: Intent): Boolean {
        if (!intent.isFreshDetailShellIntent()) return false
        val name = intent.component?.className ?: return false
        return try {
            DetailShellActivity::class.java.isAssignableFrom(Class.forName(name, false, classLoader))
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun registerChild(request: DetailShellPop.Request): ActivityResultLauncher<Intent> {
        
        val launcher = activityResultRegistry.register("detail-shell:${request.id}",
            ActivityResultContracts.StartActivityForResult()) { result ->
            if (pendingChild == request) consumeShellResult(result.resultCode, result.data)
        }
        
        if (pendingChild == request) childLauncher = launcher else unregisterAfterDispatch(launcher)
        return launcher
    }

    private fun releaseChild(request: DetailShellPop.Request) {
        if (pendingChild != request) return
        pendingChild = null
        childLauncher?.let(::unregisterAfterDispatch)
        childLauncher = null
    }

    private fun unregisterAfterDispatch(launcher: ActivityResultLauncher<Intent>) {
        launcher.unregister()
        
        
        resultCleanup.post { launcher.unregister() }
    }

    internal fun consumeShellResult(resultCode: Int, data: Intent?): Boolean {
        val pending = pendingChild ?: return false
        if (DetailShellPop.request(data) != pending) return false
        
        releaseChild(pending)
        if (resultCode != DetailShellPop.RESULT_POP_MORE) return false
        val remaining = data?.getIntExtra(DetailShellPop.EXTRA_REMAINING, 0) ?: 0
        return remaining > 0 && DetailShellPop.finish(this, remaining)
    }

    
    override fun participatesInSideChrome(): Boolean = true

    
    protected abstract fun createPage(): Fragment?

    override fun onCreate(savedInstanceState: Bundle?) {
        
        pendingChild = savedInstanceState.readRequest(STATE_PENDING)
        inheritedShellRequest = if (savedInstanceState != null) savedInstanceState.readRequest(STATE_ORIGIN)
            else DetailShellPop.request(intent)
        super.onCreate(savedInstanceState)
        
        inheritedShellRequest?.let { setResult(Activity.RESULT_CANCELED, it.writeTo(Intent())) }
        setContentView(FrameLayout(this).apply {
            id = R.id.detail_shell_container
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        pendingChild?.let { registerChild(it) }
        if (savedInstanceState != null || isFinishing) {
            supportFragmentManager.findFragmentById(R.id.detail_shell_container)?.let(::matchWindowToPage)
            return
        }
        val page = createPage()
        if (page == null) {
            L.w { "[DetailShell] no page shell=${javaClass.simpleName}" }
            finish()
            return
        }
        matchWindowToPage(page)
        supportFragmentManager.beginTransaction().replace(R.id.detail_shell_container, page).commit()
    }

    



    private fun matchWindowToPage(page: Fragment) {
        window.setBackgroundDrawableResource(if (page is SettingsIdiomPage) R.color.bg else R.color.bg1)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.writeRequest(STATE_PENDING, pendingChild)
        outState.writeRequest(STATE_ORIGIN, inheritedShellRequest)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        childLauncher?.unregister()
        childLauncher = null
        pendingChild = null
        super.onDestroy()
    }

    private fun Bundle?.readRequest(prefix: String): DetailShellPop.Request? {
        val id = this?.getString("$prefix.id")?.takeIf { it.isNotBlank() } ?: return null
        return DetailShellPop.Request(id, getString("$prefix.subject"))
    }

    private fun Bundle.writeRequest(prefix: String, request: DetailShellPop.Request?) {
        if (request == null) return
        putString("$prefix.id", request.id)
        putString("$prefix.subject", request.subjectId)
    }

    private companion object {
        const val STATE_PENDING = "detail_shell.pending"
        const val STATE_ORIGIN = "detail_shell.origin"
    }
}
