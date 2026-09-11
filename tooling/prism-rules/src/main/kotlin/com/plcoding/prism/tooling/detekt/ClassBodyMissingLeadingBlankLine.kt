package com.plcoding.prism.tooling.detekt

import com.intellij.psi.PsiWhiteSpace
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtObjectDeclaration

/**
 * Requires one blank line between a class, interface, or object header and its
 * first member.
 *
 * The header — name, supertypes, constructor parameters — is its own cognitive
 * unit; the blank line marks where it ends and the body begins. Companion
 * objects, object literals, enum bodies, and single-line bodies stay exempt:
 * their headers are trivial and the extra line would only add noise.
 */
class ClassBodyMissingLeadingBlankLine(config: Config) :
    Rule(
        config,
        "A class body should open with a blank line after the header.",
    ) {
    override fun visitClassBody(classBody: KtClassBody) {
        super.visitClassBody(classBody)

        val owner = classBody.parent
        if (owner is KtObjectDeclaration && (owner.isCompanion() || owner.isObjectLiteral())) return
        if (owner is KtClass && owner.isEnum()) return

        val lBrace = classBody.lBrace ?: return
        val leading =
            generateSequence(lBrace.nextSibling) { it.nextSibling }
                .takeWhile { it is PsiWhiteSpace }
                .joinToString("") { it.text }
        val firstContent =
            generateSequence(lBrace.nextSibling) { it.nextSibling }
                .firstOrNull { it !is PsiWhiteSpace } ?: return
        if (firstContent == classBody.rBrace) return

        if (!leading.contains("\n")) return
        if (leading.contains("\n\n")) return

        report(
            Finding(
                Entity.from(firstContent),
                "The class body starts directly under the header. Put one blank line " +
                    "after '{' before the first member.",
            ),
        )
    }
}
