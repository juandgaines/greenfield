package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Reports a `callbackFlow` that never awaits its close.
 *
 * Without `awaitClose` the builder returns as soon as the callback is
 * registered, so the flow completes immediately and the listener is never
 * unregistered. Kotlin raises this at runtime; catching it here is cheaper.
 */
class CallbackFlowRequiresAwaitClose(config: Config) :
    Rule(
        config,
        "A callbackFlow must end in awaitClose so its callback is unregistered.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != CALLBACK_FLOW) return

        val awaits =
            expression.lambdaArguments
                .flatMap { it.collectDescendantsOfType<KtCallExpression>() }
                .any { it.calleeExpression?.text == AWAIT_CLOSE }
        if (awaits) return

        report(
            Finding(
                Entity.from(expression),
                "End this callbackFlow with awaitClose { } so the callback is unregistered " +
                    "when the collector goes away.",
            ),
        )
    }

    private companion object {
        const val CALLBACK_FLOW = "callbackFlow"
        const val AWAIT_CLOSE = "awaitClose"
    }
}
