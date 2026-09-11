package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Reports state creation inside a `*Adaptive*Layout` composable.
 *
 * Adaptive layouts are pure structural containers: they arrange slots per
 * device configuration and own nothing. State lives in the ViewModel and
 * reaches the layout through the Screen composable that calls it. Sanctioned
 * `remember*State()` factories (scroll positions and the like) are the
 * configurable exception.
 */
class AdaptiveLayoutOwnsNoState(config: Config) :
    Rule(
        config,
        "An *Adaptive*Layout composable is a pure structural container and must not own state.",
    ) {
    @Configuration("remember*State factories an adaptive layout may call")
    private val allowedStateFactories: List<String> by config(
        listOf(
            "rememberScrollState",
            "rememberLazyListState",
            "rememberLazyGridState",
            "rememberLazyStaggeredGridState",
            "rememberPagerState",
            "rememberTextFieldState",
            "rememberUpdatedState",
        ),
    )

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val functionName = function.name ?: return
        if (!functionName.contains(ADAPTIVE) || !functionName.endsWith(LAYOUT_SUFFIX)) return
        val isComposable =
            function.annotationEntries.any { annotation ->
                annotation.shortName?.asString() == COMPOSABLE
            }
        if (!isComposable) return
        val body = function.bodyExpression ?: return

        body
            .collectDescendantsOfType<KtCallExpression>()
            .forEach { call ->
                val callee = call.calleeExpression?.text ?: return@forEach
                val ownsState =
                    callee in BANNED_STATE_FACTORIES ||
                        (REMEMBER_STATE_FACTORY.matches(callee) && callee !in allowedStateFactories)
                if (!ownsState) return@forEach
                report(
                    Finding(
                        Entity.from(call),
                        "Hoist this '$callee' out of '$functionName' — an adaptive layout is a " +
                            "pure structural container; state belongs in the ViewModel behind the " +
                            "Screen composable that calls it.",
                    ),
                )
            }
    }

    private companion object {
        const val ADAPTIVE = "Adaptive"
        const val LAYOUT_SUFFIX = "Layout"
        const val COMPOSABLE = "Composable"
        val BANNED_STATE_FACTORIES =
            setOf(
                "mutableStateOf",
                "mutableIntStateOf",
                "mutableLongStateOf",
                "mutableFloatStateOf",
                "mutableDoubleStateOf",
                "mutableStateListOf",
                "mutableStateMapOf",
                "rememberSaveable",
            )
        val REMEMBER_STATE_FACTORY = Regex("^remember[A-Za-z0-9]*State$")
    }
}
