package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Reports a `Result` whose error side is a collection type.
 *
 * A `Result<T, List<E>>` forces every caller to decide what a partially failed
 * operation means. The convention is one error per `Result`: validation and the
 * like return the first failure, and an operation that genuinely accumulates
 * failures models that as its own named type, not a bare collection.
 */
class NoResultListOfErrors(config: Config) :
    Rule(
        config,
        "A Result carries exactly one error, never a collection of them.",
    ) {
    @Configuration("collection type names forbidden as a Result's error side")
    private val collectionTypeNames: List<String> by config(
        listOf("List", "MutableList", "Set", "MutableSet", "Collection", "Map", "MutableMap"),
    )

    override fun visitUserType(type: KtUserType) {
        super.visitUserType(type)
        if (type.referencedName != RESULT) return
        val typeArguments = type.typeArguments
        if (typeArguments.size != 2) return
        val errorTypeName = baseName(typeArguments[1].typeReference?.typeElement) ?: return
        if (errorTypeName !in collectionTypeNames) return
        report(
            Finding(
                Entity.from(type),
                "The error side of this Result is a $errorTypeName. A Result carries one error — " +
                    "return the first failure, or model accumulated failures as a named type.",
            ),
        )
    }

    private fun baseName(typeElement: KtTypeElement?): String? =
        when (typeElement) {
            is KtNullableType -> baseName(typeElement.innerType)
            is KtUserType -> typeElement.referencedName
            else -> null
        }

    private companion object {
        const val RESULT = "Result"
    }
}
