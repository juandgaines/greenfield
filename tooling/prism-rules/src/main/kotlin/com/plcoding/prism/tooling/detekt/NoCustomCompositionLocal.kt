package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Reports a call that creates a custom `CompositionLocal`.
 *
 * A CompositionLocal is an invisible dependency: nothing in a composable's
 * signature says it reads one, and a missing provider fails at runtime. Data
 * travels through parameters. The design-system theme package is the one
 * sanctioned creation site, excluded in `detekt.yml` rather than here.
 */
class NoCustomCompositionLocal(config: Config) :
    Rule(
        config,
        "Custom CompositionLocals are not created outside the design-system theme.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text
        if (callee !in COMPOSITION_LOCAL_BUILDERS) return
        report(
            Finding(
                Entity.from(expression),
                "Don't create a CompositionLocal with $callee. Pass the value through " +
                    "parameters, or make it part of the design-system theme.",
            ),
        )
    }

    private companion object {
        val COMPOSITION_LOCAL_BUILDERS = setOf("compositionLocalOf", "staticCompositionLocalOf")
    }
}
