package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression

/**
 * Reports a `flow { }` builder whose entire body is one unconditional `emit`.
 *
 * A flow that can only ever produce a single value is a suspend function
 * wearing a stream's clothes: the builder buys cold semantics and operator
 * support that a one-shot call never uses, and the caller pays with a
 * `collect` for what is really just a return value.
 */
class OneShotFlowBuilder(config: Config) :
    Rule(
        config,
        "A flow builder that only emits a single value should be a suspend function.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != FLOW) return

        val lambda =
            expression.lambdaArguments.singleOrNull()?.getLambdaExpression()
                ?: expression.valueArguments.singleOrNull()?.getArgumentExpression() as? KtLambdaExpression
                ?: return
        val onlyStatement = lambda.bodyExpression?.statements?.singleOrNull() ?: return
        val emitCall = onlyStatement as? KtCallExpression ?: return
        if (emitCall.calleeExpression?.text != EMIT) return

        report(
            Finding(
                Entity.from(expression),
                "This flow only ever emits once. Make it a suspend function returning the value " +
                    "instead of wrapping a single emit in a flow builder.",
            ),
        )
    }

    private companion object {
        const val FLOW = "flow"
        const val EMIT = "emit"
    }
}
