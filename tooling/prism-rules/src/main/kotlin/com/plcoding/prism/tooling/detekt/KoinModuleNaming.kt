package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Reports a top-level Koin `module { }` property whose name does not follow the
 * `<feature><Layer>Module` convention.
 *
 * The name is how a module is found from the application's `includes(...)` list, so every
 * one reads the same way: a lowerCamelCase feature-and-layer prefix ending in
 * `Module`, like `featureAuthDataModule` or `coreSyncModule`.
 */
class KoinModuleNaming(config: Config) :
    Rule(
        config,
        "Top-level Koin module properties are named <feature><Layer>Module.",
    ) {
    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        if (!property.isTopLevel) return
        val initializer = property.initializer as? KtCallExpression ?: return
        if (initializer.calleeExpression?.text != MODULE_BUILDER) return
        if (initializer.lambdaArguments.isEmpty()) return
        val name = property.name ?: return
        val hasPrefix = name.length > MODULE_SUFFIX.length
        if (hasPrefix && name.endsWith(MODULE_SUFFIX) && name.first().isLowerCase()) return
        report(
            Finding(
                Entity.from(property),
                "Name this Koin module '<feature><Layer>Module' (e.g. featureAuthDataModule) " +
                    "instead of '$name'.",
            ),
        )
    }

    private companion object {
        const val MODULE_BUILDER = "module"
        const val MODULE_SUFFIX = "Module"
    }
}
