package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Reports a unique-work name literal that is not `snake_case`.
 *
 * Unique-work names are persisted identifiers WorkManager matches by exact
 * string, so the convention has to be mechanical. For an interpolated name only
 * the literal prefix is checked; a fully dynamic name cannot be judged here and
 * is left alone.
 */
class UniqueWorkNameIsSnakeCase(config: Config) :
    Rule(
        config,
        "Unique-work name literals must be snake_case.",
    ) {
    @Configuration("the unique-work enqueue functions whose name argument is checked")
    private val uniqueWorkFunctions: List<String> by config(
        listOf("enqueueUniqueWork", "enqueueUniquePeriodicWork", "beginUniqueWork"),
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text !in uniqueWorkFunctions) return
        val nameArgument = expression.nameArgument() ?: return
        val template = nameArgument.getArgumentExpression() as? KtStringTemplateExpression ?: return
        if (template.isValidWorkName()) return
        report(
            Finding(
                Entity.from(template),
                "Name this unique work in snake_case (matching $SNAKE_CASE), " +
                    "e.g. \"sync_notes\" instead of \"syncNotes\".",
            ),
        )
    }

    private fun KtCallExpression.nameArgument(): KtValueArgument? {
        val named =
            valueArguments.firstOrNull {
                it.getArgumentName()?.asName?.asString() == NAME_PARAMETER
            }
        if (named != null) return named
        return valueArguments.firstOrNull()?.takeIf { it.getArgumentName() == null }
    }

    private fun KtStringTemplateExpression.isValidWorkName(): Boolean {
        val leadingLiterals =
            entries
                .takeWhile { it is KtLiteralStringTemplateEntry }
                .joinToString("") { it.text }
        val hasOnlyLiterals = entries.all { it is KtLiteralStringTemplateEntry }
        if (!hasOnlyLiterals && leadingLiterals.isEmpty()) return true
        return SNAKE_CASE.matches(leadingLiterals)
    }

    private companion object {
        const val NAME_PARAMETER = "uniqueWorkName"
        val SNAKE_CASE = Regex("^[a-z0-9_]+$")
    }
}
