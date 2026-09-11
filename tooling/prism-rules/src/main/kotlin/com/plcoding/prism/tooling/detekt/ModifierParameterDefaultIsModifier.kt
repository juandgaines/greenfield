package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Reports a composable's `modifier` parameter that does not default to `Modifier`.
 *
 * A component whose `modifier` is required forces every call site to pass one,
 * and a default other than the empty `Modifier` bakes layout decisions into the
 * component that belong to the caller. Overrides are exempt because Kotlin
 * forbids default values on them.
 */
class ModifierParameterDefaultIsModifier(config: Config) :
    Rule(
        config,
        "A composable's modifier parameter defaults to Modifier.",
    ) {
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (!function.isComposable()) return
        if (function.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return
        val modifierParameter =
            function.valueParameters.firstOrNull { it.name == MODIFIER_PARAMETER } ?: return
        if (modifierParameter.typeReference?.text != MODIFIER_TYPE) return
        if (modifierParameter.defaultValue?.text == MODIFIER_TYPE) return
        report(
            Finding(
                Entity.from(modifierParameter),
                "Give 'modifier' the default value Modifier so callers can omit it " +
                    "and the component makes no layout decisions of its own.",
            ),
        )
    }

    private companion object {
        const val MODIFIER_PARAMETER = "modifier"
        const val MODIFIER_TYPE = "Modifier"
    }
}
