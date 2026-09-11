package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression

/**
 * Reports `_state.value = …` on a backing mutable flow.
 *
 * Read-modify-write through `.value` is two operations, so a concurrent writer
 * can be lost between them. `update { }` performs the same change atomically.
 */
class NonAtomicStateFlowAssignment(config: Config) :
    Rule(
        config,
        "Assignment to a backing MutableStateFlow's .value is not atomic - use update { }.",
    ) {
    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        if (expression.operationToken != KtTokens.EQ) return
        val left = expression.left as? KtDotQualifiedExpression ?: return
        if (left.selectorExpression?.text != "value") return
        val receiver = left.receiverExpression.text
        if (receiver.startsWith("_")) {
            report(
                Finding(
                    Entity.from(expression),
                    "Use $receiver.update { it.copy(...) } instead of assigning .value directly.",
                ),
            )
        }
    }
}
