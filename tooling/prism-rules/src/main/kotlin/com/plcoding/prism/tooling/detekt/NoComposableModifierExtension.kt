package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Reports a `@Composable` extension on `Modifier`.
 *
 * Marking a modifier factory composable couples it to composition: it can only
 * be called from a composable scope, and it re-runs with recomposition instead
 * of being the cheap value it looks like. Use `Modifier.composed` or take the
 * values it needs as parameters.
 */
class NoComposableModifierExtension(config: Config) :
    Rule(
        config,
        "A Modifier extension must not be @Composable.",
    ) {
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (function.receiverTypeReference?.text != MODIFIER) return
        if (function.annotationEntries.none { it.shortName?.asString() == COMPOSABLE }) return
        report(
            Finding(
                Entity.from(function),
                "Drop @Composable from 'Modifier.${function.name}' and pass in what it reads " +
                    "from composition instead.",
            ),
        )
    }

    private companion object {
        const val MODIFIER = "Modifier"
        const val COMPOSABLE = "Composable"
    }
}
