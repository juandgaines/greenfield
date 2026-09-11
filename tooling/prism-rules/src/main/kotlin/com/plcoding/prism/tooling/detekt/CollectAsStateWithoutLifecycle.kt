package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Reports a `collectAsState()` call.
 *
 * Plain `collectAsState` keeps collecting while the app is in the background,
 * so the upstream keeps doing work nobody can see.
 * `collectAsStateWithLifecycle` stops collection below STARTED and restarts
 * it on return, which is the behaviour every screen collection wants.
 */
class CollectAsStateWithoutLifecycle(config: Config) :
    Rule(
        config,
        "Flows are collected in composables with collectAsStateWithLifecycle, never collectAsState.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != COLLECT_AS_STATE) return

        report(
            Finding(
                Entity.from(expression),
                "Replace collectAsState() with collectAsStateWithLifecycle() so collection " +
                    "stops while the UI is not visible.",
            ),
        )
    }

    private companion object {
        const val COLLECT_AS_STATE = "collectAsState"
    }
}
