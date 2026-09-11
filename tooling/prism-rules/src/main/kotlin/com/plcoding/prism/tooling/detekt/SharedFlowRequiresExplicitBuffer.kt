package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Reports a `MutableSharedFlow` constructed with no arguments.
 *
 * The zero-argument default is replay 0 with no buffer, which silently drops
 * every emission that happens before the first subscriber arrives. Whether
 * that is acceptable is a per-flow decision, so the replay and buffer must be
 * written down even when the chosen values are the defaults.
 */
class SharedFlowRequiresExplicitBuffer(config: Config) :
    Rule(
        config,
        "A MutableSharedFlow must state its replay and extraBufferCapacity explicitly.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != MUTABLE_SHARED_FLOW) return
        if (expression.valueArguments.isNotEmpty()) return

        report(
            Finding(
                Entity.from(expression),
                "State this MutableSharedFlow's replay and extraBufferCapacity explicitly; " +
                    "the zero-argument default drops emissions made before the first subscriber.",
            ),
        )
    }

    private companion object {
        const val MUTABLE_SHARED_FLOW = "MutableSharedFlow"
    }
}
