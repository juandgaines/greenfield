package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression

/**
 * Reports a reference to the raw palette object outside the theme package.
 *
 * This is a *location* check. It says nothing about whether the entry is wired
 * into a theme at all — that is [UnwiredThemeColor]'s job. The two together are
 * what make "use the theme" mean something.
 */
class ThemeColorDirectUse(config: Config) :
    Rule(
        config,
        "Colours must be consumed through MaterialTheme, not read off the palette object.",
    ) {
    @Configuration("name of the object declaring the raw colour palette")
    private val paletteObject: String by config("")

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val paletteName = requiredPaletteObject()
        if (expression.receiverExpression.text != paletteName) return
        val token = expression.selectorExpression?.text ?: return
        report(
            Finding(
                Entity.from(expression),
                "Read '$token' through MaterialTheme instead of $paletteName directly.",
            ),
        )
    }

    /**
     * The palette object is the CONSUMER's name and has no honest default. Guessing
     * it wrong makes the receiver comparison above never match, so the rule returns
     * on every expression and reports success over the code it was pointed at --
     * the framework's own documented false-green failure. It refuses to run
     * unconfigured instead.
     *
     * Unreachable in a real install: `paletteObject` is a required parameter
     * rendered into `detekt.yml`, and `render.py` will not write a file whose
     * placeholders it could not resolve.
     */
    private fun requiredPaletteObject(): String =
        requireConsumerName(
            value = paletteObject,
            rule = "ThemeColorDirectUse",
            key = "paletteObject",
            switch = "PALETTE_RULES_ACTIVE",
        )
}
