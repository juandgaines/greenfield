package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Reports a direct import or qualified reference of `ListenableWorker.Result`.
 *
 * WorkManager's `Result` collides with the domain `Result<D, E>` the moment
 * both are in scope; the `WorkerResult` typealias exists so a Worker never has
 * to disambiguate. The typealias's own declaring file is exempt via the root
 * `detekt.yml`.
 */
class WorkerResultTypealiasRequired(config: Config) :
    Rule(
        config,
        "ListenableWorker.Result must be referenced through the WorkerResult typealias.",
    ) {
    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        if (importDirective.importedFqName?.asString() != RESULT_FQ_NAME) return
        report(
            Finding(
                Entity.from(importDirective),
                "Import the WorkerResult typealias instead of $RESULT_FQ_NAME.",
            ),
        )
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (expression.getParentOfType<KtImportDirective>(strict = true) != null) return
        val receiverText = expression.receiverExpression.text
        if (receiverText != LISTENABLE_WORKER && receiverText != LISTENABLE_WORKER_FQ_NAME) return
        val selector = expression.selectorExpression as? KtNameReferenceExpression ?: return
        if (selector.getReferencedName() != RESULT) return
        report(
            Finding(
                Entity.from(expression),
                "Use the WorkerResult typealias instead of referencing $LISTENABLE_WORKER.$RESULT directly.",
            ),
        )
    }

    override fun visitUserType(type: KtUserType) {
        super.visitUserType(type)
        if (type.referencedName != RESULT) return
        val qualifierText = type.qualifier?.text ?: return
        if (qualifierText != LISTENABLE_WORKER && qualifierText != LISTENABLE_WORKER_FQ_NAME) return
        report(
            Finding(
                Entity.from(type),
                "Use the WorkerResult typealias instead of the $LISTENABLE_WORKER.$RESULT type.",
            ),
        )
    }

    private companion object {
        const val LISTENABLE_WORKER = "ListenableWorker"
        const val RESULT = "Result"
        const val LISTENABLE_WORKER_FQ_NAME = "androidx.work.ListenableWorker"
        const val RESULT_FQ_NAME = "androidx.work.ListenableWorker.Result"
    }
}
