package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Reports a child composable that takes a screen-state parameter.
 *
 * The screen-level state stops at the `*Screen`/`*Root` pair. A child that
 * takes the whole state recomposes whenever anything in it changes; passing
 * only the values it reads keeps recompositions scoped. Compose-owned state
 * holders such as `LazyListState` are hoisted UI plumbing, not screen state,
 * and stay allowed.
 */
class ScreenStateOnlyInScreenComposable(config: Config) :
    Rule(
        config,
        "Screen-level state may only be a parameter of Screen or Root composables.",
    ) {
    @Configuration("Compose-owned state-holder types a child composable may accept")
    private val allowedStateTypes: List<String> by config(
        listOf(
            "LazyListState",
            "LazyGridState",
            "LazyStaggeredGridState",
            "ScrollState",
            "TextFieldState",
            "PagerState",
            "SheetState",
            "SnackbarHostState",
            "DrawerState",
            "TooltipState",
            "PullToRefreshState",
            "TopAppBarState",
        ),
    )

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val annotationNames = function.annotationEntries.mapNotNull { it.shortName?.asString() }
        if (COMPOSABLE !in annotationNames) return
        if (annotationNames.any { it.startsWith(PREVIEW) }) return
        val functionName = function.name ?: return
        if (functionName.endsWith(SCREEN) || functionName.endsWith(ROOT)) return

        function.valueParameters.forEach { parameter ->
            val typeName = parameter.referencedTypeName() ?: return@forEach
            if (!typeName.endsWith(STATE)) return@forEach
            if (typeName in allowedStateTypes) return@forEach
            report(
                Finding(
                    Entity.from(parameter),
                    "Don't pass '$typeName' into '$functionName'. Pass the specific values the " +
                        "child needs so recompositions stay scoped to what actually changed.",
                ),
            )
        }
    }

    private fun KtParameter.referencedTypeName(): String? {
        var typeElement = typeReference?.typeElement
        while (typeElement is KtNullableType) {
            typeElement = typeElement.innerType
        }
        return (typeElement as? KtUserType)?.referencedName
    }

    private companion object {
        const val COMPOSABLE = "Composable"
        const val PREVIEW = "Preview"
        const val SCREEN = "Screen"
        const val ROOT = "Root"
        const val STATE = "State"
    }
}
