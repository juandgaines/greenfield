package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Reports a `NavGraphBuilder` extension function whose name does not end in
 * `Graph`.
 *
 * A feature exposes exactly one navigation entry point, named
 * `<feature>Graph`, so the app module can wire graphs without reading each
 * feature's internals. Any other name hides that contract.
 */
class NavGraphBuilderExtensionNaming(config: Config) :
    Rule(
        config,
        "NavGraphBuilder extension functions must be named <feature>Graph.",
    ) {
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val receiverText = function.receiverTypeReference?.text ?: return
        if (receiverText != NAV_GRAPH_BUILDER && !receiverText.endsWith(".$NAV_GRAPH_BUILDER")) return
        val functionName = function.name ?: return
        if (functionName.endsWith(GRAPH_SUFFIX)) return

        report(
            Finding(
                Entity.from(function),
                "Rename '$functionName' to end in 'Graph' — a NavGraphBuilder extension is " +
                    "a feature's navigation entry point and follows the <feature>Graph convention.",
            ),
        )
    }

    private companion object {
        const val NAV_GRAPH_BUILDER = "NavGraphBuilder"
        const val GRAPH_SUFFIX = "Graph"
    }
}
