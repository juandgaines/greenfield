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
 * Reports a preview that never wraps its content in the theme composable.
 *
 * An unthemed preview renders with default Material colours and typography, so
 * it shows a component that ships nowhere. A preview may delegate to a helper;
 * the search follows calls into same-file functions before deciding, because a
 * shared preview body that themes once is the correct shape, not a violation.
 */
class PreviewMustWrapInTheme(config: Config) :
    Rule(
        config,
        "Every preview wraps its content in the app theme composable.",
    ) {
    @Configuration("name of the theme composable a preview must wrap its content in")
    private val themeName: String by config("")

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (!function.isPreview()) return
        val theme = requiredThemeName()
        if (function.wrapsInTheme(theme, mutableSetOf())) return
        report(
            Finding(
                Entity.from(function),
                "Wrap '${function.name}'s content in $theme so the preview renders " +
                    "with the app theme.",
            ),
        )
    }

    /**
     * The theme composable is the CONSUMER's name and has no honest default. This
     * rule's failure is loud rather than silent -- a wrong name matches no call, so
     * every preview in the repository is reported, each message naming a composable
     * the consumer has never heard of. That is not a gate, it is noise that gets the
     * rule switched off and takes the two silent theme rules with it.
     *
     * It therefore refuses to run unconfigured, exactly as [ThemeColorDirectUse] and
     * [UnwiredThemeColor] do for `paletteObject` and `extendedTokenHolder`.
     *
     * Unreachable in a real install: `themeName` is a required parameter rendered
     * into `detekt.yml` from `@THEME_OBJECT@`, and `render.py` will not write a file
     * whose placeholders it could not resolve.
     */
    private fun requiredThemeName(): String =
        requireConsumerName(
            value = themeName,
            rule = "PreviewMustWrapInTheme",
            key = "themeName",
            switch = "THEME_RULES_ACTIVE",
        )

    private fun KtNamedFunction.wrapsInTheme(themeName: String, visitedNames: MutableSet<String>): Boolean {
        val calls = collectDescendantsOfType<KtCallExpression>()
        if (calls.any { it.calleeExpression?.text == themeName }) return true
        val sameFileFunctions =
            containingKtFile
                .collectDescendantsOfType<KtNamedFunction>()
                .mapNotNull { candidate -> candidate.name?.let { it to candidate } }
                .toMap()
        return calls.any { call ->
            val calleeName = call.calleeExpression?.text
            calleeName != null &&
                visitedNames.add(calleeName) &&
                sameFileFunctions[calleeName]?.wrapsInTheme(themeName, visitedNames) == true
        }
    }
}
