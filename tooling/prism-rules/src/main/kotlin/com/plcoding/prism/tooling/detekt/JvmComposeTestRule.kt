package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Reports the JVM `createComposeRule()` variants.
 *
 * Compose UI tests run instrumented against a real Activity;
 * `createAndroidComposeRule<ComponentActivity>()` is the only sanctioned rule
 * factory. The plain variants host content in a bare test harness that diverges
 * from real lifecycle and window behavior.
 */
class JvmComposeTestRule(config: Config) :
    Rule(
        config,
        "Compose tests use createAndroidComposeRule<ComponentActivity>(), never the plain createComposeRule variants.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text ?: return
        if (callee !in FORBIDDEN_FACTORIES) return
        report(
            Finding(
                Entity.from(expression),
                "Replace $callee() with createAndroidComposeRule<ComponentActivity>() so the " +
                    "test runs against a real Activity.",
            ),
        )
    }

    private companion object {
        val FORBIDDEN_FACTORIES = setOf("createComposeRule", "createEmptyComposeRule")
    }
}
