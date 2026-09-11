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
 * Reports a `Room.inMemoryDatabaseBuilder(...)` call in a test class that
 * declares no `@After`/`@AfterEach` teardown calling `close()`.
 *
 * An in-memory Room database holds an open SQLite connection until it is
 * closed; a suite that never closes it leaks a connection per test. The
 * teardown lookup is structural: any function in the same class annotated with
 * a teardown annotation whose body calls `close` — bare or qualified — counts.
 */
class InMemoryRoomNotClosed(config: Config) :
    Rule(
        config,
        "A test class that builds an in-memory Room database must close() it in an @After/@AfterEach teardown.",
    ) {
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (expression.receiverExpression.text != ROOM) return
        val selectorCall = expression.selectorExpression as? KtCallExpression ?: return
        if (selectorCall.calleeExpression?.text != IN_MEMORY_BUILDER) return

        val containingClass = expression.getStrictParentOfType<KtClassOrObject>() ?: return
        if (containingClass.hasTeardownCalling(CLOSE)) return

        report(
            Finding(
                Entity.from(expression),
                "Add an @After (or @AfterEach) function that calls close() on this database, " +
                    "so its SQLite connection does not leak across tests.",
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
        const val ROOM = "Room"
        const val IN_MEMORY_BUILDER = "inMemoryDatabaseBuilder"
        const val CLOSE = "close"
        val TEARDOWN_ANNOTATIONS = setOf("After", "AfterEach")
    }
}
