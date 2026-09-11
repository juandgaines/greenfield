package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Reports an `HttpClient` construction.
 *
 * The client carries the auth, refresh, logging, and serialization plugins, so a
 * second construction site is a client missing some of them. The project's
 * `HttpClientFactory` — the one sanctioned site — and the test sources are
 * excluded by configuration.
 */
class HttpClientConstructionOutsideFactory(config: Config) :
    Rule(
        config,
        "HttpClient is constructed only in HttpClientFactory.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != HTTP_CLIENT) return
        report(
            Finding(
                Entity.from(expression),
                "Inject the HttpClient built by HttpClientFactory instead of " +
                    "constructing one here — a second client misses the shared plugins.",
            ),
        )
    }

    private companion object {
        const val HTTP_CLIENT = "HttpClient"
    }
}
