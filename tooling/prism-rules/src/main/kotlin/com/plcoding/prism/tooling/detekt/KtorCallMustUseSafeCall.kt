package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * Reports an HTTP verb called straight on the client.
 *
 * `safeGet` / `safePost` / `safeDelete` are the canonical wrappers: they convert
 * a transport throw into a typed error and map the status code once, in one
 * place. Calling the verb directly re-implements that at the call site, usually
 * incompletely.
 *
 * The module that implements the wrappers is excluded by configuration — it has
 * to call the verbs to wrap them.
 */
class KtorCallMustUseSafeCall(config: Config) :
    Rule(
        config,
        "HTTP calls go through the project's safeGet / safePost / safeDelete wrappers.",
    ) {
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        // No type resolution here, so the receiver is matched by the name the project
        // gives its client everywhere. A differently named client would slip past, which
        // is why the wrappers are also the reviewed convention rather than only a rule.
        if (expression.receiverExpression.text !in CLIENT_RECEIVERS) return
        val verb = (expression.selectorExpression as? KtCallExpression)?.calleeExpression?.text ?: return
        if (verb !in HTTP_VERBS) return
        // A verb called inside safeCall { } is going through the wrapper -- that is the
        // sanctioned form, and the wrapper needs the verb to wrap.
        if (expression.parents
                .filterIsInstance<KtCallExpression>()
                .any { it.calleeExpression?.text in SAFE_WRAPPERS }
        ) {
            return
        }
        report(
            Finding(
                Entity.from(expression),
                "Call this through safe${verb.replaceFirstChar { it.uppercase() }} so the transport " +
                    "error and the status code are mapped in one place.",
            ),
        )
    }

    private companion object {
        val CLIENT_RECEIVERS = setOf("httpClient", "client")
        val HTTP_VERBS = setOf("get", "post", "put", "patch", "delete")
        val SAFE_WRAPPERS = setOf("safeCall", "safeGet", "safePost", "safePut", "safePatch", "safeDelete")
    }
}
