package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * Reports a `Dispatchers.setMain(...)` call in a test class that declares no
 * `@AfterEach`/`@After` teardown calling `Dispatchers.resetMain()`.
 *
 * A replaced main dispatcher outlives the test that installed it, so a missing
 * reset leaks into every test that runs after it in the same JVM. The teardown
 * lookup is structural: any function in the same class annotated with a
 * teardown annotation whose body calls `resetMain` counts.
 */
class DispatchersSetMainWithoutReset(config: Config) :
    Rule(
        config,
        "A test class that calls Dispatchers.setMain must reset it in an @AfterEach/@After teardown.",
    ) {
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (expression.receiverExpression.text != DISPATCHERS) return
        val selectorCall = expression.selectorExpression as? KtCallExpression ?: return
        if (selectorCall.calleeExpression?.text != SET_MAIN) return

        val containingClass = expression.getStrictParentOfType<KtClassOrObject>() ?: return
        if (containingClass.hasTeardownCalling(RESET_MAIN)) return

        report(
            Finding(
                Entity.from(expression),
                "Add an @AfterEach (or @After) function calling Dispatchers.resetMain() " +
                    "to this class, so the replaced main dispatcher does not leak into other tests.",
            ),
        )
    }

    private fun KtClassOrObject.hasTeardownCalling(calleeName: String): Boolean =
        declarations
            .filterIsInstance<KtNamedFunction>()
            .filter { function -> function.isTeardown() }
            .any { teardown ->
                teardown.bodyExpression?.anyDescendantOfType<KtCallExpression> { call ->
                    call.calleeExpression?.text == calleeName
                } == true
            }

    private fun KtNamedFunction.isTeardown(): Boolean =
        annotationEntries.any { annotationEntry ->
            annotationEntry.shortName?.asString() in TEARDOWN_ANNOTATIONS
        }

    private companion object {
        const val DISPATCHERS = "Dispatchers"
        const val SET_MAIN = "setMain"
        const val RESET_MAIN = "resetMain"
        val TEARDOWN_ANNOTATIONS = setOf("AfterEach", "After")
    }
}
