package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtObjectDeclaration

/**
 * Reports a `*Route` declared as a plain `object` or plain `class`.
 *
 * Routes are `data object` (no arguments) or `data class` (arguments): the
 * generated `equals` is what lets the back stack and `hasRoute` compare
 * destinations by value. Sealed parents of a route hierarchy are exempt —
 * they are never instantiated as destinations themselves.
 */
class RouteMustBeDataObjectOrDataClass(config: Config) :
    Rule(
        config,
        "Routes must be declared as data object (no args) or data class (args).",
    ) {
    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        val routeName = classOrObject.name ?: return
        if (!routeName.endsWith(ROUTE_SUFFIX)) return

        when (classOrObject) {
            is KtObjectDeclaration -> {
                if (classOrObject.isCompanion() || classOrObject.isObjectLiteral()) return
                if (classOrObject.hasModifier(KtTokens.DATA_KEYWORD)) return
                report(
                    Finding(
                        Entity.from(classOrObject),
                        "Declare '$routeName' as a data object so destinations compare by value.",
                    ),
                )
            }

            is KtClass -> {
                if (classOrObject.isInterface() || classOrObject.isEnum() || classOrObject.isAnnotation()) return
                if (classOrObject.hasModifier(KtTokens.SEALED_KEYWORD)) return
                if (classOrObject.hasModifier(KtTokens.ABSTRACT_KEYWORD)) return
                if (classOrObject.hasModifier(KtTokens.VALUE_KEYWORD)) return
                if (classOrObject.isData()) return
                report(
                    Finding(
                        Entity.from(classOrObject),
                        "Declare '$routeName' as a data class so destinations compare by value.",
                    ),
                )
            }
        }
    }

    private companion object {
        const val ROUTE_SUFFIX = "Route"
    }
}
