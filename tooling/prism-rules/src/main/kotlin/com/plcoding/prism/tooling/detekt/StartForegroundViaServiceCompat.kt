package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression

/**
 * Reports the raw two-argument `startForeground(id, notification)` overload.
 *
 * On API 34+ that overload throws when the manifest declares a foreground
 * service type; `ServiceCompat.startForeground` passes the type explicitly and
 * degrades correctly on older APIs.
 */
class StartForegroundViaServiceCompat(config: Config) :
    Rule(
        config,
        "The raw two-argument startForeground overload must be replaced with ServiceCompat.startForeground.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != START_FOREGROUND) return
        if (expression.valueArguments.size != EXPECTED_ARGUMENT_COUNT) return
        if (expression.isQualifiedByServiceCompat()) return
        report(
            Finding(
                Entity.from(expression),
                "Call ServiceCompat.startForeground(service, id, notification, foregroundServiceType) " +
                    "instead of the raw two-argument startForeground overload.",
            ),
        )
    }

    private fun KtCallExpression.isQualifiedByServiceCompat(): Boolean {
        val qualified = parent as? KtDotQualifiedExpression ?: return false
        if (qualified.selectorExpression != this) return false
        val receiverText = qualified.receiverExpression.text
        return receiverText == SERVICE_COMPAT || receiverText == SERVICE_COMPAT_FQ_NAME
    }

    private companion object {
        const val START_FOREGROUND = "startForeground"
        const val SERVICE_COMPAT = "ServiceCompat"
        const val SERVICE_COMPAT_FQ_NAME = "androidx.core.app.ServiceCompat"
        const val EXPECTED_ARGUMENT_COUNT = 2
    }
}
