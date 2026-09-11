package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression

/**
 * Reports a Room database builder in a JVM unit test.
 *
 * Room needs a real SQLite implementation, which only exists on a device;
 * building it on the JVM either crashes or runs against a stand-in that does
 * not match production. Room tests are instrumented. Scoping to `src/test`
 * happens in the root `detekt.yml`, not here.
 */
class RoomInJvmUnitTest(config: Config) :
    Rule(
        config,
        "Room tests are instrumented; database builders do not belong under src/test.",
    ) {
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (expression.receiverExpression.text != ROOM_RECEIVER) return
        val selectorCall = expression.selectorExpression as? KtCallExpression ?: return
        val builder = selectorCall.calleeExpression?.text ?: return
        if (builder !in DATABASE_BUILDERS) return
        report(
            Finding(
                Entity.from(expression),
                "Move this Room.$builder test to src/androidTest — Room tests are instrumented.",
            ),
        )
    }

    private companion object {
        const val ROOM_RECEIVER = "Room"
        val DATABASE_BUILDERS = setOf("inMemoryDatabaseBuilder", "databaseBuilder")
    }
}
