package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Reports a mutable flow that is visible outside its owner.
 *
 * Exposing the mutable type hands every consumer a write handle to state the
 * owner is supposed to be the only author of. Keep the `MutableStateFlow`
 * private and publish it as a read-only `StateFlow`.
 */
class NoPublicMutableFlow(config: Config) :
    Rule(
        config,
        "A mutable flow must not be visible outside the class that owns it.",
    ) {
    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        if (property.hasModifier(KtTokens.PRIVATE_KEYWORD)) return

        val declaredType = property.typeReference?.text
        val initializerCall = property.initializer?.text?.substringBefore('(')
        val isMutableFlow =
            MUTABLE_FLOW_TYPES.any { declaredType?.startsWith(it) == true || initializerCall?.trim() == it }
        if (!isMutableFlow) return

        report(
            Finding(
                Entity.from(property),
                "Make '${property.name}' private and expose it as a read-only StateFlow or SharedFlow.",
            ),
        )
    }

    private companion object {
        val MUTABLE_FLOW_TYPES = setOf("MutableStateFlow", "MutableSharedFlow")
    }
}
