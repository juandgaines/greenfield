package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression

/**
 * Reports a colour built from a literal outside the theme package.
 *
 * A hex value at a call site is a colour no theme can reach, so it cannot follow
 * the scheme. Define it once in the palette and consume it through
 * `MaterialTheme`.
 */
class RawColorLiteral(config: Config) :
    Rule(
        config,
        "Colour literals belong in the design-system theme, not at a call site.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != COLOR) return
        val hasLiteralArgument =
            expression.valueArgumentList
                ?.arguments
                .orEmpty()
                .any { it.getArgumentExpression() is KtConstantExpression }
        if (!hasLiteralArgument) return
        report(
            Finding(
                Entity.from(expression),
                "Define this colour in the design-system palette and read it through MaterialTheme.",
            ),
        )
    }

    private companion object {
        const val COLOR = "Color"
    }
}
