package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Reports a defaulted parameter declared before `modifier` in a composable.
 *
 * The Compose parameter order is: required parameters, then `modifier`, then
 * optional parameters, then slots. A defaulted parameter ahead of `modifier`
 * breaks that contract at every call site that passes `modifier` positionally.
 * `@Composable` slot parameters are [ComposableSlotParameterAfterModifier]'s
 * concern, not this rule's.
 */
class ModifierParameterAfterRequiredParameters(config: Config) :
    Rule(
        config,
        "Only required parameters come before a composable's modifier parameter.",
    ) {
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (!function.isComposable()) return
        val parameters = function.valueParameters
        val modifierIndex = parameters.indexOfFirst { it.name == MODIFIER_PARAMETER }
        if (modifierIndex < 0) return
        parameters
            .take(modifierIndex)
            .filter { it.hasDefaultValue() && !it.isComposableSlot() }
            .forEach { parameter ->
                report(
                    Finding(
                        Entity.from(parameter),
                        "'${parameter.name}' has a default value, so it is optional. " +
                            "Move it after 'modifier'; only required parameters come before it.",
                    ),
                )
            }
    }

    private companion object {
        const val MODIFIER_PARAMETER = "modifier"
    }
}
