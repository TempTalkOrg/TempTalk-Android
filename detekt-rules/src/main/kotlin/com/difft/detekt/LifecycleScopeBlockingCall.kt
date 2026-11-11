package com.difft.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtQualifiedExpression
























class LifecycleScopeBlockingCall(config: Config) : Rule(
    config,
    "lifecycleScope/viewModelScope.launch without explicit non-Main dispatcher.",
) {

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        if (expression.calleeExpression?.text != LAUNCH) return
        if (!isMainBoundScope(expression)) return
        if (hasNonMainDispatcherArg(expression)) return

        report(
            Finding(
                entity = Entity.from(expression),
                message = "$LAUNCH on a Main-bound scope without explicit dispatcher; " +
                    "blocking code inside this lambda runs on the UI thread. " +
                    "Pass `Dispatchers.IO`/`Dispatchers.Default`, or `@Suppress` " +
                    "if the body is verifiably non-blocking.",
            )
        )
    }

    
    private fun isMainBoundScope(launchCall: KtCallExpression): Boolean {
        
        
        
        val qualified = launchCall.parent as? KtQualifiedExpression ?: return false
        
        
        
        
        val receiverText = qualified.receiverExpression.text
        
        return MAIN_BOUND_SCOPES.any { scope ->
            receiverText == scope || receiverText.endsWith(".$scope")
        }
    }

    
    private fun hasNonMainDispatcherArg(launchCall: KtCallExpression): Boolean {
        
        
        val explicitArgs = launchCall.valueArguments.filter { it !is KtLambdaArgument }
        return explicitArgs.any { arg ->
            val text = arg.text ?: return@any false
            NON_MAIN_DISPATCHER_MARKERS.any { marker -> marker in text }
        }
    }

    private companion object {
        const val LAUNCH = "launch"
        val MAIN_BOUND_SCOPES = setOf(
            "lifecycleScope",
            "viewModelScope",
            
        )
        
        
        val NON_MAIN_DISPATCHER_MARKERS = listOf(
            "Dispatchers.IO",
            "Dispatchers.Default",
            "Dispatchers.Unconfined",
        )
    }
}
