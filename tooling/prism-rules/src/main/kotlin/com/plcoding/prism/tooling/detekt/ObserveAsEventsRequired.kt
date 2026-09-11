package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Reports a `LaunchedEffect` that collects a flow.
 *
 * One-time events go through the project's `ObserveAsEvents` helper, which ties
 * collection to the lifecycle and drops nothing on the way. A bare
 * `LaunchedEffect` + `collect` keeps collecting while the screen is in the
 * background and delivers events no one is there to see.
 */
class ObserveAsEventsRequired(config: Config) :
    Rule(
        config,
        "One-time events are observed through ObserveAsEvents, not a LaunchedEffect that collects.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != LAUNCHED_EFFECT) return

        val collects =
            expression.lambdaArguments
                .flatMap { it.collectDescendantsOfType<KtDotQualifiedExpression>() }
                .any { it.selectorExpression?.let(::isCollectCall) == true }
        if (!collects) return

        report(
            Finding(
                Entity.from(expression),
                "Collect this through ObserveAsEvents so collection " +
                    "follows the lifecycle.",
            ),
        )
    }

    private fun isCollectCall(selector: org.jetbrains.kotlin.psi.KtExpression): Boolean =
        (selector as? KtCallExpression)?.calleeExpression?.text in COLLECT_FUNCTIONS

    private companion object {
        const val LAUNCHED_EFFECT = "LaunchedEffect"
        val COLLECT_FUNCTIONS = setOf("collect", "collectLatest")
    }
}
