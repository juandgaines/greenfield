package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Reports `rememberTextFieldState` inside a composable.
 *
 * Screen-level form state belongs to the ViewModel, where it survives
 * configuration changes and process death and can be asserted on in a unit
 * test. Remembering it in the composable puts it out of reach of both.
 */
class TextFieldStateInViewModel(config: Config) :
    Rule(
        config,
        "Screen-level text-field state belongs to the ViewModel, not to a composable.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != REMEMBER_TEXT_FIELD_STATE) return
        val enclosing = expression.getParentOfType<KtNamedFunction>(strict = true) ?: return
        if (enclosing.annotationEntries.none { it.shortName?.asString() == COMPOSABLE }) return
        report(
            Finding(
                Entity.from(expression),
                "Hold this TextFieldState in the ViewModel and pass it in, so it survives " +
                    "configuration changes and stays testable.",
            ),
        )
    }

    private companion object {
        const val REMEMBER_TEXT_FIELD_STATE = "rememberTextFieldState"
        const val COMPOSABLE = "Composable"
    }
}
