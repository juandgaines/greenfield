package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Reports a palette entry that no theme reads.
 *
 * Being declared next to the theme is not what makes a colour a theme colour;
 * being wired into a [androidx.compose.material3.ColorScheme] slot or into the
 * extended-token holder is. An entry that no wiring call references cannot be
 * reached through `MaterialTheme` at all, so every use of it necessarily
 * bypasses the theme — which is the defect [ThemeColorDirectUse] can only see
 * the shadow of.
 *
 * The rule is single-file by construction: it reads the palette object and the
 * wiring calls out of the same [KtFile]. That holds only while `Color.kt`
 * declares the object, both schemes, and the extended-token holder together. If
 * that file is ever split the rule reports every entry as unwired rather than
 * quietly passing, and `UnwiredThemeColorTest` pins that behaviour so the
 * failure is loud and the design gets revisited.
 */
class UnwiredThemeColor(config: Config) :
    Rule(
        config,
        "A palette entry that no colour scheme or extended token references is unreachable through MaterialTheme.",
    ) {
    @Configuration("name of the object declaring the raw colour palette")
    private val paletteObject: String by config("")

    @Configuration("name of the class holding the extended colour tokens")
    private val extendedTokenHolder: String by config("")

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        val paletteName = required(paletteObject, "paletteObject")
        val holderName = required(extendedTokenHolder, "extendedTokenHolder")
        val palette =
            file
                .collectDescendantsOfType<KtObjectDeclaration> { it.name == paletteName }
                .firstOrNull() ?: return

        val wired = wiredTokenNames(file, holderName)
        palette
            .collectDescendantsOfType<KtProperty>()
            .filter { it.name != null && it.name !in wired }
            .forEach { property ->
                report(
                    Finding(
                        Entity.from(property),
                        "'${property.name}' is not wired into a colour scheme or into " +
                            "$holderName, so it cannot be reached through MaterialTheme. " +
                            "Wire it in, or delete it.",
                    ),
                )
            }
    }

    /**
     * The palette and the token holder are the CONSUMER's names, and there is no
     * honest default for either: guessing wrong makes `collectDescendantsOfType`
     * match nothing and the rule return before it reports anything, which is a
     * silent pass over the exact code it was pointed at. That is the framework's
     * own documented false-green failure, so the rule refuses to run unconfigured
     * rather than running and finding nothing.
     *
     * In a real install this cannot fire: both are required parameters rendered
     * into `detekt.yml`, and `render.py` will not write a file whose placeholders
     * it could not resolve. It fires for a hand-edited config that dropped the key.
     */
    private fun required(value: String, name: String): String =
        requireConsumerName(
            value = value,
            rule = "UnwiredThemeColor",
            key = name,
            switch = "PALETTE_RULES_ACTIVE",
        )

    private fun wiredTokenNames(file: KtFile, holderName: String): Set<String> {
        val schemeCalls =
            file.collectDescendantsOfType<KtCallExpression> {
                val callee = it.calleeExpression?.text
                // Constructing the token holder wires whatever it is passed, so its own
                // constructor call is a wiring call -- and its name is the consumer's,
                // which is why this set is assembled here rather than being a constant.
                callee in COMPOSE_WIRING_CALLS || callee == holderName
            }
        // The extended-token holder wires its colours as default parameter values on the
        // class itself rather than at a call site, so the declaration counts as wiring too.
        val holderDeclaration =
            file.collectDescendantsOfType<KtClass> { it.name == holderName }
        return (schemeCalls + holderDeclaration)
            .flatMap { it.collectDescendantsOfType<KtSimpleNameExpression>() }
            .map { it.getReferencedName() }
            .toSet()
    }

    private companion object {
        /** Material's own scheme factories. Framework names, so they stay literal. */
        val COMPOSE_WIRING_CALLS =
            setOf(
                "darkColorScheme",
                "lightColorScheme",
                "ColorScheme",
            )
    }
}
