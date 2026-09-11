package com.plcoding.prism.tooling.detekt

import com.intellij.psi.PsiWhiteSpace
import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Reports a run of more than [maxRunLength] consecutive statements with no
 * blank line anywhere in the run.
 *
 * A wall of statements forces the reader to find the logical steps themselves.
 * The rule cannot know where the steps are — only that a body this long
 * without any break has none — so it flags the wall and leaves the grouping
 * to the author.
 */
class CrammedBlockBody(config: Config) :
    Rule(
        config,
        "Long unbroken statement runs hide the logical steps of a body.",
    ) {
    @Configuration("maximum consecutive statements allowed without a separating blank line")
    private val maxRunLength: Int by config(5)

    override fun visitBlockExpression(expression: KtBlockExpression) {
        super.visitBlockExpression(expression)

        var runLength = 0
        var reportedThisRun = false
        var previous: KtExpression? = null

        for (statement in expression.statements) {
            if (previous == null || hasBlankLineBetween(previous, statement)) {
                runLength = 1
                reportedThisRun = false
            } else {
                runLength++
            }

            if (runLength > maxRunLength && !reportedThisRun) {
                report(
                    Finding(
                        Entity.from(statement),
                        "More than $maxRunLength consecutive statements without a blank line. " +
                            "Split this body into groups by semantic meaning: keep statements that " +
                            "form one cognitive step together and separate the groups with single " +
                            "blank lines. Choose the boundaries by meaning — do not just insert a " +
                            "blank line every $maxRunLength statements.",
                    ),
                )
                reportedThisRun = true
            }

            previous = statement
        }
    }

    private fun hasBlankLineBetween(first: KtExpression, second: KtExpression): Boolean {
        var sibling = first.nextSibling

        while (sibling != null && sibling != second) {
            if (sibling is PsiWhiteSpace && sibling.text.contains("\n\n")) return true
            sibling = sibling.nextSibling
        }

        return false
    }
}
