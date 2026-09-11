package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Reports the type `Result<Unit, E>` written out instead of `EmptyResult<E>`.
 *
 * The typealias exists so a signature says "this can only fail" in one word.
 * The file declaring the typealias is the one place that must spell the type
 * out, and it is excluded by configuration.
 */
class EmptyResultOverResultUnit(config: Config) :
    Rule(
        config,
        "A Result with no success value is written as the EmptyResult typealias.",
    ) {
    override fun visitUserType(type: KtUserType) {
        super.visitUserType(type)
        if (type.referencedName != RESULT) return
        val typeArguments = type.typeArguments
        if (typeArguments.size != 2) return
        val successType = typeArguments[0].typeReference?.typeElement as? KtUserType ?: return
        if (successType.referencedName != UNIT) return
        val errorTypeText = typeArguments[1].typeReference?.text ?: return
        report(
            Finding(
                Entity.from(type),
                "Write this as EmptyResult<$errorTypeText> instead of Result<Unit, $errorTypeText>.",
            ),
        )
    }

    private companion object {
        const val RESULT = "Result"
        const val UNIT = "Unit"
    }
}
