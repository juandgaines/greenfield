package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Reports a production class or function opened for subclassing.
 *
 * `open` in shipped code is almost always there so a test can substitute a
 * subclass. Extract an interface instead: the seam then exists in the design
 * rather than only in the test, and the production class stays final.
 */
class NoOpenClassInMain(config: Config) :
    Rule(
        config,
        "Production types are not opened for subclassing. Extract an interface instead.",
    ) {
    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        // An abstract class is meant to be extended, and its members carry `open`
        // as part of that contract rather than as a testing seam.
        if (klass.hasModifier(KtTokens.ABSTRACT_KEYWORD) || klass.isSealed() || klass.isInterface()) return
        reportIfOpen(klass, "class")
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val owner = function.containingClassOrObject as? KtClass
        if (owner != null && (owner.hasModifier(KtTokens.ABSTRACT_KEYWORD) || owner.isSealed())) return
        reportIfOpen(function, "function")
    }

    private fun reportIfOpen(declaration: KtNamedDeclaration, kind: String) {
        if (!declaration.hasModifier(KtTokens.OPEN_KEYWORD)) return
        report(
            Finding(
                Entity.from(declaration),
                "'${declaration.name}' is an open $kind in shipped code. " +
                    "Extract an interface rather than opening it for a test double.",
            ),
        )
    }
}
