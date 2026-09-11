package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * Reports a lambda Koin definition that is just a constructor call fed by bare
 * `get()` arguments.
 *
 * `singleOf(::X)` / `factoryOf(::X)` / `viewModelOf(::X)` say the same thing in
 * one token and cannot drift out of sync with the constructor's parameter list.
 * Anything that carries information the reference form cannot express — named
 * arguments, qualifiers, type arguments, a chained `bind` — is left alone,
 * because there the lambda is the point.
 */
class PreferConstructorReferenceKoinDefinition(config: Config) :
    Rule(
        config,
        "A Koin definition that only calls a constructor with get() uses the constructor-reference overload.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text ?: return
        val replacement = REPLACEMENTS[callee] ?: return
        if (!expression.isInsideModuleBlock()) return
        if (expression.typeArgumentList != null) return
        if (expression.valueArgumentList?.arguments?.isNotEmpty() == true) return
        if (expression.isChainedIntoBinding()) return
        val lambda = expression.lambdaArguments.singleOrNull()?.getLambdaExpression() ?: return
        if (lambda.valueParameters.isNotEmpty()) return
        val body = lambda.bodyExpression?.statements?.singleOrNull() as? KtCallExpression ?: return
        val constructed = (body.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return
        if (!constructed.first().isUpperCase()) return
        if (body.typeArgumentList != null) return
        if (body.lambdaArguments.isNotEmpty()) return
        if (!body.valueArguments.all { argument -> argument.isBareGetCall() }) return
        report(
            Finding(
                Entity.from(expression),
                "Replace this definition with $replacement(::$constructed) — the lambda only " +
                    "calls the constructor with get().",
            ),
        )
    }

    private fun KtCallExpression.isInsideModuleBlock(): Boolean =
        parents
            .filterIsInstance<KtCallExpression>()
            .any { call -> call.calleeExpression?.text == MODULE_BUILDER }

    private fun KtCallExpression.isChainedIntoBinding(): Boolean =
        parent is KtBinaryExpression || parent is KtDotQualifiedExpression

    private fun KtValueArgument.isBareGetCall(): Boolean {
        if (getArgumentName() != null) return false
        val argument = getArgumentExpression() as? KtCallExpression ?: return false
        if (argument.calleeExpression?.text != GET) return false
        return argument.typeArgumentList == null &&
            argument.valueArguments.isEmpty() &&
            argument.lambdaArguments.isEmpty()
    }

    private companion object {
        const val MODULE_BUILDER = "module"
        const val GET = "get"
        val REPLACEMENTS =
            mapOf(
                "single" to "singleOf",
                "factory" to "factoryOf",
                "viewModel" to "viewModelOf",
            )
    }
}
