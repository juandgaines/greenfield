package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression

/**
 * Reports an `HttpClient` construction that names no engine.
 *
 * The engine-less overload asks Ktor to discover one from the classpath, which
 * works until a dependency change quietly swaps the engine — and it leaves tests
 * nothing to substitute a `MockEngine` into. Passing the engine (or its factory)
 * keeps that seam explicit.
 */
class HttpClientMustAcceptEngine(config: Config) :
    Rule(
        config,
        "HttpClient is constructed with an explicit engine or engine factory as its first argument.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != HTTP_CLIENT) return
        val firstArgument =
            expression.valueArgumentList
                ?.arguments
                ?.firstOrNull()
                ?.getArgumentExpression()
        if (firstArgument != null && firstArgument !is KtLambdaExpression) return
        report(
            Finding(
                Entity.from(expression),
                "Pass an engine or engine factory (e.g. HttpClient(CIO) { ... }) instead of " +
                    "letting Ktor pick one from the classpath.",
            ),
        )
    }

    private companion object {
        const val HTTP_CLIENT = "HttpClient"
    }
}
