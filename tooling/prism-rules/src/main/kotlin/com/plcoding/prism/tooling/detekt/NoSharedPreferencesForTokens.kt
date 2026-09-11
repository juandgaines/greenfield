package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

/**
 * Reports `getSharedPreferences` calls and any reference to
 * `EncryptedSharedPreferences`.
 *
 * Persistence in this project is DataStore, with Keystore-backed encryption
 * where secrecy matters. `EncryptedSharedPreferences` is deprecated and
 * unmaintained, and plain `SharedPreferences` would put tokens on disk in the
 * clear.
 */
class NoSharedPreferencesForTokens(config: Config) :
    Rule(
        config,
        "SharedPreferences APIs are forbidden; persistence goes through DataStore.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != GET_SHARED_PREFERENCES) return
        report(
            Finding(
                Entity.from(expression),
                "Persist this through DataStore instead of SharedPreferences.",
            ),
        )
    }

    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
        super.visitSimpleNameExpression(expression)
        if (expression.getReferencedName() != ENCRYPTED_SHARED_PREFERENCES) return
        report(
            Finding(
                Entity.from(expression),
                "EncryptedSharedPreferences is deprecated — persist this through DataStore, " +
                    "with Keystore-backed encryption where secrecy matters.",
            ),
        )
    }

    private companion object {
        const val GET_SHARED_PREFERENCES = "getSharedPreferences"
        const val ENCRYPTED_SHARED_PREFERENCES = "EncryptedSharedPreferences"
    }
}
