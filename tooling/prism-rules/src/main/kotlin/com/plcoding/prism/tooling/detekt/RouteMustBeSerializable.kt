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
 * Reports a `*Route` declaration that does not carry `@Serializable`.
 *
 * Type-safe Navigation serialises every destination, including the sealed
 * parent of a route hierarchy — a route without the annotation fails at
 * runtime, not at compile time. Non-sealed interfaces and enums are exempt:
 * an interface is never a destination instance, and enums serialise without
 * the annotation.
 */
class RouteMustBeSerializable(config: Config) :
    Rule(
        config,
        "Every route declaration must carry @Serializable for type-safe navigation.",
    ) {
    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        val routeName = classOrObject.name ?: return
        if (!routeName.endsWith(ROUTE_SUFFIX)) return
        if (classOrObject is KtObjectDeclaration &&
            (classOrObject.isCompanion() || classOrObject.isObjectLiteral())
        ) {
            return
        }
        if (classOrObject is KtClass) {
            if (classOrObject.isInterface() && !classOrObject.hasModifier(KtTokens.SEALED_KEYWORD)) return
            if (classOrObject.isEnum() || classOrObject.isAnnotation()) return
        }
        val isSerializable =
            classOrObject.annotationEntries.any { annotation ->
                annotation.shortName?.asString() == SERIALIZABLE
            }
        if (isSerializable) return

        report(
            Finding(
                Entity.from(classOrObject),
                "Annotate '$routeName' with @Serializable so type-safe navigation can " +
                    "serialise it as a destination.",
            ),
        )
    }

    private companion object {
        const val ROUTE_SUFFIX = "Route"
        const val SERIALIZABLE = "Serializable"
    }
}
