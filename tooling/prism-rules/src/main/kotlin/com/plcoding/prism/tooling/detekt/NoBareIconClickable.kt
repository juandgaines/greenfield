package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Reports an `Icon` made clickable through its modifier.
 *
 * A bare clickable icon is typically 24dp, well under the 48dp minimum touch
 * target, and it gets no ripple. `IconButton` supplies both.
 */
class NoBareIconClickable(config: Config) :
    Rule(
        config,
        "A clickable Icon misses the minimum touch target and the ripple. Wrap it in an IconButton.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != ICON) return

        val modifierArgument =
            expression.valueArgumentList
                ?.arguments
                .orEmpty()
                .firstOrNull { it.getArgumentName()?.asName?.asString() == MODIFIER } ?: return
        val isClickable =
            modifierArgument
                .collectDescendantsOfType<KtCallExpression>()
                .any { it.calleeExpression?.text in CLICK_MODIFIERS }
        if (!isClickable) return

        report(
            Finding(
                Entity.from(expression),
                "Wrap this Icon in an IconButton instead of making it clickable directly, " +
                    "so it keeps its 48dp touch target and its ripple.",
            ),
        )
    }

    private companion object {
        const val ICON = "Icon"
        const val MODIFIER = "modifier"
        val CLICK_MODIFIERS = setOf("clickable", "combinedClickable")
    }
}
