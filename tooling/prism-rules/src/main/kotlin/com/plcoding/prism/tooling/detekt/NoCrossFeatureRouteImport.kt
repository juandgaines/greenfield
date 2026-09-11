package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Reports a feature module importing another feature's route.
 *
 * Features never navigate into each other directly — cross-feature navigation
 * is a callback the app module wires. The application module, which does the
 * wiring, is exempt via the activation excludes.
 */
class NoCrossFeatureRouteImport(config: Config) :
    Rule(
        config,
        "A feature module must not import another feature's route; cross-feature navigation is a callback.",
    ) {
    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val importSegments =
            importDirective.importedFqName?.pathSegments()?.map { segment -> segment.asString() } ?: return
        val importsRoute =
            importSegments.any { segment ->
                segment.endsWith(ROUTE_SUFFIX) && segment.first().isUpperCase()
            }
        if (!importsRoute) return

        val importedFeature = importSegments.featureName() ?: return
        val packageSegments =
            importDirective.containingKtFile.packageFqName
                .pathSegments()
                .map { segment -> segment.asString() }
        val ownFeature = packageSegments.featureName() ?: return
        if (importedFeature == ownFeature) return

        report(
            Finding(
                Entity.from(importDirective),
                "Feature '$ownFeature' imports a route of feature '$importedFeature'. " +
                    "Expose a callback parameter instead and let the app module wire the navigation.",
            ),
        )
    }

    private fun List<String>.featureName(): String? {
        val featureIndex = indexOf(FEATURE_SEGMENT)
        if (featureIndex == -1) return null
        return getOrNull(featureIndex + 1)
    }

    private companion object {
        const val ROUTE_SUFFIX = "Route"
        const val FEATURE_SEGMENT = "feature"
    }
}
