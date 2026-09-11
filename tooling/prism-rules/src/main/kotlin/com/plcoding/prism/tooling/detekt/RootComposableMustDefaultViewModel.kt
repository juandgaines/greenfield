package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Reports a Root composable's ViewModel parameter without a `koinViewModel()`
 * default.
 *
 * The default is what lets navigation call the Root with no arguments while
 * tests still inject a fake. A ViewModel parameter without it forces every
 * call site to resolve the ViewModel itself.
 */
class RootComposableMustDefaultViewModel(config: Config) :
    Rule(
        config,
        "A Root composable's ViewModel parameter defaults to koinViewModel().",
    ) {
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (function.annotationEntries.none { it.shortName?.asString() == COMPOSABLE }) return
        val functionName = function.name ?: return
        if (!functionName.endsWith(ROOT)) return

        function.valueParameters.forEach { parameter ->
            val typeName = parameter.referencedTypeName() ?: return@forEach
            if (!typeName.endsWith(VIEW_MODEL)) return@forEach
            if (parameter.defaultsToKoinViewModel()) return@forEach
            report(
                Finding(
                    Entity.from(parameter),
                    "Give the '$typeName' parameter of '$functionName' the default " +
                        "'$KOIN_VIEW_MODEL()', so navigation can call the Root without arguments.",
                ),
            )
        }
    }

    private fun KtParameter.defaultsToKoinViewModel(): Boolean =
        (defaultValue as? KtCallExpression)?.calleeExpression?.text == KOIN_VIEW_MODEL

    private fun KtParameter.referencedTypeName(): String? {
        var typeElement = typeReference?.typeElement
        while (typeElement is KtNullableType) {
            typeElement = typeElement.innerType
        }
        return (typeElement as? KtUserType)?.referencedName
    }

    private companion object {
        const val COMPOSABLE = "Composable"
        const val ROOT = "Root"
        const val VIEW_MODEL = "ViewModel"
        const val KOIN_VIEW_MODEL = "koinViewModel"
    }
}
