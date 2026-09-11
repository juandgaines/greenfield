package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Reports `runBlocking` in shipped code.
 *
 * Blocking a thread to wait on a coroutine is how the main thread stalls and how
 * a structured-concurrency scope gets bypassed. The only legitimate use is an
 * application entry point, which has no caller to suspend into.
 */
class NoRunBlockingInMain(config: Config) :
    Rule(
        config,
        "runBlocking does not belong in shipped code outside an entry point.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != RUN_BLOCKING) return
        if (expression.getParentOfType<KtNamedFunction>(strict = true)?.name == ENTRY_POINT) return
        report(
            Finding(
                Entity.from(expression),
                "Make the caller suspend, or launch into a scope, instead of blocking on runBlocking.",
            ),
        )
    }

    private companion object {
        const val RUN_BLOCKING = "runBlocking"
        const val ENTRY_POINT = "main"
    }
}
