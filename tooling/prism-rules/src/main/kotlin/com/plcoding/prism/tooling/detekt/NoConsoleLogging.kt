package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression

/**
 * Reports `println` and `android.util.Log` calls in shipped code.
 *
 * Console writes bypass whatever redaction the real logger applies, which is how
 * a secret ends up in logcat.
 */
class NoConsoleLogging(config: Config) :
    Rule(
        config,
        "Console logging must not appear in shipped code.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text ?: return
        if (callee in CONSOLE_FUNCTIONS) {
            report(Finding(Entity.from(expression), "Remove the '$callee' call from shipped code."))
        }
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val receiver = expression.receiverExpression.text
        if (receiver != "Log" && receiver != "android.util.Log") return
        val call = expression.selectorExpression as? KtCallExpression ?: return
        if (call.calleeExpression?.text !in LOG_LEVELS) return
        report(Finding(Entity.from(expression), "Remove the android.util.Log call from shipped code."))
    }

    private companion object {
        val CONSOLE_FUNCTIONS = setOf("println", "print")
        val LOG_LEVELS = setOf("v", "d", "i", "w", "e", "wtf")
    }
}
