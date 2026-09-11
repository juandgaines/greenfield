package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Reports a preview function that is not `private`.
 *
 * A preview is file-local scaffolding for the component beside it. Anything
 * wider invites other code to call it, and a preview called from production is
 * hardcoded sample data on a real screen.
 */
class PreviewMustBePrivate(config: Config) :
    Rule(
        config,
        "Preview functions are private.",
    ) {
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (!function.isPreview()) return
        if (function.hasModifier(KtTokens.PRIVATE_KEYWORD)) return
        report(
            Finding(
                Entity.from(function),
                "Make '${function.name}' private. A preview is file-local scaffolding, " +
                    "not API for other code to call.",
            ),
        )
    }
}
