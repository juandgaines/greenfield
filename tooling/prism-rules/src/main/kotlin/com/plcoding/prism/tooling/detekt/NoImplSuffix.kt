package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtClassOrObject

/**
 * Reports a class, object, or interface whose name ends in `Impl`.
 *
 * `Impl` says only "this one exists". An implementation is named for what makes
 * it unique — the technology, the strategy, the data source — so a second
 * implementation never forces a rename and the call site reads like a decision.
 */
class NoImplSuffix(config: Config) :
    Rule(
        config,
        "Implementations are named for what makes them unique, never with an Impl suffix.",
    ) {
    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        val name = classOrObject.name ?: return
        if (!name.endsWith(IMPL_SUFFIX)) return
        report(
            Finding(
                Entity.from(classOrObject),
                "Rename '$name': name the type for what makes it unique " +
                    "(its technology, strategy, or data source), not with an Impl suffix.",
            ),
        )
    }

    private companion object {
        const val IMPL_SUFFIX = "Impl"
    }
}
