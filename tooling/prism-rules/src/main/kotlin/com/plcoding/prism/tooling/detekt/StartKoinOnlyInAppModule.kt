package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Reports a `startKoin` call.
 *
 * The graph is assembled exactly once, in the Application class; a second
 * `startKoin` anywhere else either crashes at runtime or silently builds a
 * parallel graph. The Application class and the test sources are excluded
 * by configuration — they are the sanctioned call sites.
 */
class StartKoinOnlyInAppModule(config: Config) :
    Rule(
        config,
        "startKoin is called only in the Application class.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != START_KOIN) return
        report(
            Finding(
                Entity.from(expression),
                "Move this startKoin call into the Application class — the graph is " +
                    "assembled once, there, and nowhere else.",
            ),
        )
    }

    private companion object {
        const val START_KOIN = "startKoin"
    }
}
