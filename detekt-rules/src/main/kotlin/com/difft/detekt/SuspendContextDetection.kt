package com.difft.detekt

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtQualifiedExpression






















internal enum class SuspendCoverage {
    
    COVERED,

    
    BUILDER_BOUNDARY,

    
    UNCOVERED,

    
    NOT_SUSPEND,
}








internal val COROUTINE_BUILDER_NAMES = setOf(
    "launch", "async", "runBlocking", "flow", "produce", "channelFlow",
    "callbackFlow",
)

internal fun analyzeSuspendCoverage(call: KtCallExpression): SuspendCoverage {
    var node: PsiElement? = call.parent
    while (node != null) {
        when (node) {
            is KtLambdaExpression -> {
                
                val enclosingCall = findCallTakingLambda(node)
                val callee = enclosingCall?.calleeExpression?.text
                when {
                    callee == "withContext" -> return SuspendCoverage.COVERED
                    callee in COROUTINE_BUILDER_NAMES -> return SuspendCoverage.BUILDER_BOUNDARY
                    
                }
            }
            is KtNamedFunction -> {
                return if (node.hasModifier(KtTokens.SUSPEND_KEYWORD)) {
                    SuspendCoverage.UNCOVERED
                } else {
                    SuspendCoverage.NOT_SUSPEND
                }
            }
        }
        node = node.parent
    }
    return SuspendCoverage.NOT_SUSPEND
}






private fun findCallTakingLambda(lambda: KtLambdaExpression): KtCallExpression? {
    
    val asArg = lambda.parent as? KtLambdaArgument
    if (asArg != null) return asArg.parent as? KtCallExpression
    
    var node: PsiElement? = lambda.parent
    while (node != null) {
        if (node is KtCallExpression) return node
        if (node is KtNamedFunction) return null
        node = node.parent
    }
    return null
}













internal fun KtCallExpression.rootReceiverText(): String? {
    val qualified = parent as? KtQualifiedExpression ?: return null
    var receiver: PsiElement = qualified.receiverExpression
    while (receiver is KtQualifiedExpression) {
        receiver = receiver.receiverExpression
    }
    return receiver.text
}
