package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Reports a Screen composable whose `state` and `onAction` parameters are out
 * of order.
 *
 * Every Screen reads the same way: `state` first, `onAction` second, then the
 * optional rest. A swapped pair is harmless to the compiler and jarring to
 * every reader who has internalised the convention.
 */
class ScreenComposableParameterOrder(config: Config) :
    Rule(
        config,
        "A Screen composable takes state as its first parameter and onAction as its second.",
    ) {
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (function.annotationEntries.none { it.shortName?.asString() == COMPOSABLE }) return
        val functionName = function.name ?: return
        if (!functionName.endsWith(SCREEN)) return

        val parameterNames = function.valueParameters.map { it.name }
        if (STATE !in parameterNames || ON_ACTION !in parameterNames) return
        if (parameterNames.getOrNull(0) == STATE && parameterNames.getOrNull(1) == ON_ACTION) return

        report(
            Finding(
                Entity.from(function),
                "Reorder the parameters of '$functionName' so that '$STATE' comes first " +
                    "and '$ON_ACTION' second.",
            ),
        )
    }

    private companion object {
        const val COMPOSABLE = "Composable"
        const val SCREEN = "Screen"
        const val STATE = "state"
        const val ON_ACTION = "onAction"
    }
}
