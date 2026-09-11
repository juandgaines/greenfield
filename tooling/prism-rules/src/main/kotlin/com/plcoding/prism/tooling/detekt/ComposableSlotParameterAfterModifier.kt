package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Reports a defaulted `@Composable` slot parameter declared before `modifier`.
 *
 * Slots go last so a trailing-lambda call site stays possible and the
 * parameter list reads required → modifier → optional → slots. A required
 * slot may lead — that is Material 3's own idiom (`ListItem(headlineContent,
 * modifier, ...)`) — but a slot with a default is an optional parameter and
 * belongs after `modifier`.
 */
class ComposableSlotParameterAfterModifier(config: Config) :
    Rule(
        config,
        "Composable slot parameters come after the modifier parameter.",
    ) {
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (!function.isComposable()) return
        val parameters = function.valueParameters
        val modifierIndex = parameters.indexOfFirst { it.name == MODIFIER_PARAMETER }
        if (modifierIndex < 0) return
        parameters
            .take(modifierIndex)
            .filter { it.isComposableSlot() && it.hasDefaultValue() }
            .forEach { parameter ->
                report(
                    Finding(
                        Entity.from(parameter),
                        "Declare the slot '${parameter.name}' after 'modifier', " +
                            "at the end of the parameter list.",
                    ),
                )
            }
    }

    private companion object {
        const val MODIFIER_PARAMETER = "modifier"
    }
}
