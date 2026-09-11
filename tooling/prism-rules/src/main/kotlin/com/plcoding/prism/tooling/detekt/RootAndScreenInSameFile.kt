package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Reports a `<X>Root` composable whose file declares no `<X>Screen` composable.
 *
 * The Root wires the ViewModel and the Screen renders the state; keeping the
 * pair in one file is what makes the split navigable. A Root without its
 * Screen next to it either lost the split or scattered it across files.
 */
class RootAndScreenInSameFile(config: Config) :
    Rule(
        config,
        "A Root composable and its Screen composable live in the same file.",
    ) {
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (!function.isComposable()) return
        val functionName = function.name ?: return
        if (!functionName.endsWith(ROOT)) return
        val screenName = functionName.removeSuffix(ROOT) + SCREEN

        val screenExists =
            function.containingKtFile
                .collectDescendantsOfType<KtNamedFunction>()
                .any { candidate -> candidate.name == screenName && candidate.isComposable() }
        if (screenExists) return

        report(
            Finding(
                Entity.from(function),
                "Declare the '$screenName' composable in this file next to '$functionName', " +
                    "so the Root/Screen pair stays together.",
            ),
        )
    }

    private fun KtNamedFunction.isComposable(): Boolean =
        annotationEntries.any { it.shortName?.asString() == COMPOSABLE }

    private companion object {
        const val COMPOSABLE = "Composable"
        const val ROOT = "Root"
        const val SCREEN = "Screen"
    }
}
