package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Reports an `Icon` that passes no content description at all.
 *
 * `contentDescription = null` is a decision — it tells a screen reader the icon
 * is decorative. Leaving the argument out is not a decision, it just leaves the
 * icon unannounced, so absence is the violation and an explicit `null` is not.
 */
class IconContentDescription(config: Config) :
    Rule(
        config,
        "Every Icon must state a content description, explicitly null if it is decorative.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != ICON) return

        // Trailing lambdas live outside the argument list, so reading the list directly
        // keeps the positional count honest.
        val arguments = expression.valueArgumentList?.arguments.orEmpty()
        if (arguments.any { it.getArgumentName()?.asName?.asString() == CONTENT_DESCRIPTION }) return
        // Icon's second positional parameter is contentDescription, so two unnamed
        // arguments means it was supplied.
        if (arguments.count { it.getArgumentName() == null } >= 2) return

        report(
            Finding(
                Entity.from(expression),
                "Add a contentDescription to this Icon, or pass null to mark it decorative.",
            ),
        )
    }

    private companion object {
        const val ICON = "Icon"
        const val CONTENT_DESCRIPTION = "contentDescription"
    }
}
