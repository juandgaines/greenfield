package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Reports an `IconButton` whose modifier chain pins its size.
 *
 * The default `IconButton` size is what guarantees the 48dp minimum touch
 * target; sizing it down keeps the visual and loses the target. Size the `Icon`
 * inside it instead.
 */
class IconButtonHardcodedSize(config: Config) :
    Rule(
        config,
        "An IconButton sized by its modifier loses the 48dp minimum touch target.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != ICON_BUTTON) return

        val modifierArgument =
            expression.valueArguments
                .firstOrNull { it.getArgumentName()?.asName?.asString() == MODIFIER } ?: return

        val sizesItself =
            modifierArgument
                .collectDescendantsOfType<KtCallExpression>()
                .any { it.calleeExpression?.text == SIZE }
        if (!sizesItself) return

        report(
            Finding(
                Entity.from(expression),
                "Drop the .size() from this IconButton's modifier. Size the Icon inside it instead, " +
                    "so the button keeps its 48dp touch target.",
            ),
        )
    }

    private companion object {
        const val ICON_BUTTON = "IconButton"
        const val MODIFIER = "modifier"
        const val SIZE = "size"
    }
}
