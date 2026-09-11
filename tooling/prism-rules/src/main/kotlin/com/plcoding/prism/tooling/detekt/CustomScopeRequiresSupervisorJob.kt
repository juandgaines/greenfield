package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Reports a hand-built `CoroutineScope` whose context has no `SupervisorJob`.
 *
 * With a plain `Job` — literal or the implicit one a bare dispatcher context
 * gets — one failed child cancels every sibling in the scope. A context passed
 * in from outside or a named job variable is left alone: the caller may
 * already carry a supervisor, and guessing would fire on correct code.
 */
class CustomScopeRequiresSupervisorJob(config: Config) :
    Rule(
        config,
        "A hand-built CoroutineScope must include a SupervisorJob in its context.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != COROUTINE_SCOPE) return
        val contextArgument = expression.valueArguments.singleOrNull() ?: return

        val callsInContext = contextArgument.collectDescendantsOfType<KtCallExpression>()
        if (callsInContext.any { call -> call.calleeExpression?.text == SUPERVISOR_JOB }) return

        val buildsLiteralJob = callsInContext.any { call -> call.calleeExpression?.text == JOB }
        val dispatchersOnly = isBareDispatchers(contextArgument.getArgumentExpression())
        if (!buildsLiteralJob && !dispatchersOnly) return

        report(
            Finding(
                Entity.from(expression),
                "Add SupervisorJob() to this CoroutineScope's context so one failed child " +
                    "cannot cancel its siblings.",
            ),
        )
    }

    private fun isBareDispatchers(expression: KtExpression?): Boolean =
        when (expression) {
            is KtDotQualifiedExpression -> {
                expression.receiverExpression.text == DISPATCHERS
            }

            is KtParenthesizedExpression -> {
                isBareDispatchers(expression.expression)
            }

            is KtBinaryExpression -> {
                expression.operationReference.text == PLUS &&
                    isBareDispatchers(expression.left) &&
                    isBareDispatchers(expression.right)
            }

            else -> {
                false
            }
        }

    private companion object {
        const val COROUTINE_SCOPE = "CoroutineScope"
        const val SUPERVISOR_JOB = "SupervisorJob"
        const val JOB = "Job"
        const val DISPATCHERS = "Dispatchers"
        const val PLUS = "+"
    }
}
