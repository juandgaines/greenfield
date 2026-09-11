package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * Reports user-facing text written as a literal.
 *
 * Anything a person reads comes from a string resource, so it can be translated
 * and changed without touching a composable. Only string *literals* count — a
 * `stringResource(...)` call passed to the same parameter is the correct form.
 *
 * Placeholder copy inside a preview never ships, so previews are exempt. That
 * exemption lives in the rule rather than in `ignoreAnnotated` so that a test can
 * prove it works -- detekt applies the config-level suppressor outside the rule,
 * where `lint()` cannot see it, and an exclusion nobody can test is an exclusion
 * nobody knows the shape of.
 */
class HardcodedUserFacingString(config: Config) :
    Rule(
        config,
        "User-facing strings come from string resources.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.isInsidePreview()) return
        val arguments = expression.valueArgumentList?.arguments.orEmpty()

        if (expression.calleeExpression?.text == TEXT) {
            val firstPositional = arguments.firstOrNull { it.getArgumentName() == null }
            reportIfLiteral(firstPositional)
        }
        arguments
            .filter { it.getArgumentName()?.asName?.asString() in USER_FACING_ARGUMENTS }
            .forEach(::reportIfLiteral)
    }

    private fun reportIfLiteral(argument: KtValueArgument?) {
        val template = argument?.getArgumentExpression() as? KtStringTemplateExpression ?: return
        // An interpolated template still has a literal shell, but the values come from
        // somewhere; only a wholly literal string is unambiguously hardcoded copy.
        if (template.hasInterpolation()) return
        report(
            Finding(
                Entity.from(template),
                "Move this text into a string resource and read it with stringResource().",
            ),
        )
    }

    // Multi-preview annotations are named for what they vary -- ThemePreviews,
    // PhonePreviews, PreviewLightDark -- so the word is what identifies them and
    // neither a prefix nor a suffix match would catch them all.
    private fun KtCallExpression.isInsidePreview(): Boolean =
        parents.filterIsInstance<KtNamedFunction>().any { function ->
            function.annotationEntries.any { it.shortName?.asString()?.contains(PREVIEW) == true }
        }

    private companion object {
        const val PREVIEW = "Preview"
        const val TEXT = "Text"
        val USER_FACING_ARGUMENTS = setOf("text", "contentDescription")
    }
}
