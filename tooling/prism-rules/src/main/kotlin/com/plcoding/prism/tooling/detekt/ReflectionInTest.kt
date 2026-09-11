package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Reports reflection into private members from tests.
 *
 * A test that reflects past visibility is coupled to the implementation it
 * claims to verify. Test through the public API, or extract an interface, so
 * the test survives a refactor the behavior survives.
 */
class ReflectionInTest(config: Config) :
    Rule(
        config,
        "Tests never reflect into private members; test through the public API instead.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text ?: return
        if (callee !in REFLECTIVE_LOOKUPS) return
        report(
            Finding(
                Entity.from(expression),
                "Remove this $callee call — test through the public API or extract an " +
                    "interface instead of reflecting into private members.",
            ),
        )
    }

    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        if (expression.operationToken != KtTokens.EQ) return
        if (expression.right?.text != TRUE_LITERAL) return
        val target = expression.left
        val targetName =
            when (target) {
                is KtDotQualifiedExpression -> target.selectorExpression?.text
                else -> target?.text
            }
        if (targetName != IS_ACCESSIBLE) return
        report(
            Finding(
                Entity.from(expression),
                "Remove this isAccessible override — test through the public API instead of " +
                    "opening private members.",
            ),
        )
    }

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val imported = importDirective.importedFqName?.asString() ?: return
        if (REFLECTION_PREFIXES.none { reflectionPrefix -> imported.startsWith(reflectionPrefix) }) return
        report(
            Finding(
                Entity.from(importDirective),
                "Drop '$imported' — tests do not reflect into private members.",
            ),
        )
    }

    private companion object {
        val REFLECTIVE_LOOKUPS = setOf("getDeclaredField", "getDeclaredMethod")
        val REFLECTION_PREFIXES = listOf("java.lang.reflect", "kotlin.reflect.full")
        const val IS_ACCESSIBLE = "isAccessible"
        const val TRUE_LITERAL = "true"
    }
}
