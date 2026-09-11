package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Reports `Thread.sleep` and `Espresso.onIdle` in tests.
 *
 * Both are wall-clock waits: they make the suite slow when the wait is long
 * enough and flaky when it is not. Synchronize on the thing being awaited
 * instead — the compose test clock, Turbine, or an idling resource.
 */
class ThreadSleepOrEspressoIdleInTest(config: Config) :
    Rule(
        config,
        "Tests never wait on the wall clock via Thread.sleep or Espresso.onIdle.",
    ) {
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val receiver = expression.receiverExpression.text
        val selector = expression.selectorExpression
        val selectorName =
            when (selector) {
                is KtCallExpression -> selector.calleeExpression?.text
                else -> selector?.text
            } ?: return
        val isThreadSleep = receiver == THREAD_RECEIVER && selectorName == SLEEP
        val isEspressoIdle = receiver == ESPRESSO_RECEIVER && selectorName == ON_IDLE
        if (!isThreadSleep && !isEspressoIdle) return
        report(
            Finding(
                Entity.from(expression),
                "Replace $receiver.$selectorName with synchronization on the awaited state — " +
                    "the compose test clock, Turbine, or an idling resource.",
            ),
        )
    }

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val imported = importDirective.importedFqName?.asString() ?: return
        if (imported != ON_IDLE_IMPORT) return
        report(
            Finding(
                Entity.from(importDirective),
                "Drop the Espresso.onIdle import — synchronize on the awaited state instead.",
            ),
        )
    }

    private companion object {
        const val THREAD_RECEIVER = "Thread"
        const val SLEEP = "sleep"
        const val ESPRESSO_RECEIVER = "Espresso"
        const val ON_IDLE = "onIdle"
        const val ON_IDLE_IMPORT = "androidx.test.espresso.Espresso.onIdle"
    }
}
