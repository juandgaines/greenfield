package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtPostfixExpression

/**
 * Reports the not-null assertion operator.
 *
 * `!!` converts a nullable value into a crash. Handle the null: `?.`, `?:`, a
 * `requireNotNull` that says what went wrong, or a type that cannot be null.
 */
class NoNotNullAssertion(config: Config) :
    Rule(
        config,
        "The not-null assertion operator turns a nullable value into a crash.",
    ) {
    override fun visitPostfixExpression(expression: KtPostfixExpression) {
        super.visitPostfixExpression(expression)
        if (expression.operationToken != KtTokens.EXCLEXCL) return
        report(
            Finding(
                Entity.from(expression),
                "Handle the null case instead of asserting it away with '!!'.",
            ),
        )
    }
}
