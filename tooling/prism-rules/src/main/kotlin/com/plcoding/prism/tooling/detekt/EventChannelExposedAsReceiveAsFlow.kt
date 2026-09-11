package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Reports a ViewModel property that exposes a raw `Channel`.
 *
 * A `Channel` handed out directly lets any collaborator send into the event
 * stream and close it. The ViewModel keeps the `Channel` private and exposes
 * only the read side via `receiveAsFlow()`.
 */
class EventChannelExposedAsReceiveAsFlow(config: Config) :
    Rule(
        config,
        "A ViewModel keeps its event Channel private and exposes it via receiveAsFlow().",
    ) {
    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        if (property.isLocal) return
        if (property.hasModifier(KtTokens.PRIVATE_KEYWORD)) return
        val containingClass = property.getParentOfType<KtClass>(strict = true) ?: return
        if (containingClass.name?.endsWith(VIEW_MODEL) != true) return
        if (!property.declaresChannelType() && !property.initializesBareChannel()) return

        report(
            Finding(
                Entity.from(property),
                "Keep this Channel private and expose the events as a separate " +
                    "'receiveAsFlow()' property instead.",
            ),
        )
    }

    private fun KtProperty.declaresChannelType(): Boolean {
        var typeElement = typeReference?.typeElement
        while (typeElement is KtNullableType) {
            typeElement = typeElement.innerType
        }
        return (typeElement as? KtUserType)?.referencedName == CHANNEL
    }

    private fun KtProperty.initializesBareChannel(): Boolean =
        (initializer as? KtCallExpression)?.calleeExpression?.text == CHANNEL

    private companion object {
        const val VIEW_MODEL = "ViewModel"
        const val CHANNEL = "Channel"
    }
}
