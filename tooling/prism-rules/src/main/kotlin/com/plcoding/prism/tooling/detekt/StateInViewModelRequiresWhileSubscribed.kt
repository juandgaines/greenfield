package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Reports a `stateIn`/`shareIn` on `viewModelScope` started eagerly or lazily.
 *
 * `Eagerly` and `Lazily` keep the upstream running for the whole ViewModel
 * lifetime, screen visible or not. `WhileSubscribed(5_000L)` stops it when the
 * UI is gone and survives a configuration change, which is the only behaviour
 * a ViewModel-scoped share should have.
 */
class StateInViewModelRequiresWhileSubscribed(config: Config) :
    Rule(
        config,
        "stateIn/shareIn on viewModelScope must use SharingStarted.WhileSubscribed(5_000L).",
    ) {
    @Configuration("scope arguments that mark a sharing call as ViewModel-scoped")
    private val viewModelScopeNames: List<String> by config(listOf("viewModelScope"))

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val calleeName = expression.calleeExpression?.text ?: return
        if (calleeName !in SHARING_CALLS) return

        val argumentTexts =
            expression.valueArguments.mapNotNull { argument -> argument.getArgumentExpression()?.text }
        if (argumentTexts.none { argumentText -> argumentText in viewModelScopeNames }) return
        val forbiddenStart =
            argumentTexts.firstOrNull { argumentText -> argumentText in FORBIDDEN_STARTS } ?: return

        report(
            Finding(
                Entity.from(expression),
                "Replace $forbiddenStart with SharingStarted.WhileSubscribed(5_000L) so this " +
                    "$calleeName stops its upstream when the UI is gone.",
            ),
        )
    }

    private companion object {
        val SHARING_CALLS = setOf("stateIn", "shareIn")
        val FORBIDDEN_STARTS = setOf("SharingStarted.Eagerly", "SharingStarted.Lazily")
    }
}
