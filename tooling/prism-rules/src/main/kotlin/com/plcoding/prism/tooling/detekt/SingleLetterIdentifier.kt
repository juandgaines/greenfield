package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Reports single-letter `val`, `var`, and parameter names.
 *
 * A name should say what it holds. `it` stays available for trivial lambdas, and
 * a single-letter loop index keeps the one conventional reading everyone shares.
 */
class SingleLetterIdentifier(config: Config) :
    Rule(
        config,
        "Single-letter identifiers do not say what they hold.",
    ) {
    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        reportIfSingleLetter(property)
    }

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        if (parameter.isLoopParameter && parameter.name in LOOP_INDICES) return
        reportIfSingleLetter(parameter)
    }

    private fun reportIfSingleLetter(declaration: KtNamedDeclaration) {
        val name = declaration.name ?: return
        if (name.length != 1 || name in ALWAYS_ALLOWED) return
        report(
            Finding(
                Entity.from(declaration),
                "'$name' does not say what it holds. Name it after its meaning.",
            ),
        )
    }

    private val KtParameter.isLoopParameter: Boolean
        get() = parent is KtForExpression || parent?.parent is KtForExpression

    private companion object {
        val ALWAYS_ALLOWED = setOf("it", "_")
        val LOOP_INDICES = setOf("i", "j", "k")
    }
}
