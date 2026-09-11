package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RequiresAnalysisApi
import dev.detekt.api.Rule
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfTypes

/**
 * Reports a generic `catch` in a suspending context that does not re-raise
 * cancellation before any other handling.
 *
 * A coroutine's cancellation signal travels as a [CancellationException]
 * through the same `catch (e: Exception)` that handles real failures. A catch
 * that swallows it keeps the coroutine running after its scope has cancelled
 * it. The catch must first rethrow the exception when it is a
 * `CancellationException`, or call `ensureActive()` on the coroutine context,
 * before handling anything else.
 *
 * Every trigger is resolved, never name-matched: the caught type, the
 * suspending-ness of the enclosing callable (a lambda counts through the
 * resolved suspend function type of the parameter it is passed to, whatever
 * the builder is called), the `ensureActive` callee, and the
 * `CancellationException` check all come from the Analysis API.
 */
class SuspendGenericCatchSwallowsCancellation(config: Config) :
    Rule(
        config,
        "A generic catch in a suspending context must re-raise cancellation " +
            "before any other handling.",
    ),
    RequiresAnalysisApi {
    override fun visitCatchSection(catchClause: KtCatchClause) {
        super.visitCatchSection(catchClause)
        val caughtTypeReference = catchClause.catchParameter?.typeReference ?: return

        analyze(catchClause) {
            if (!isGenericThrowableType(caughtTypeReference)) return
            val callable = nearestEnclosingCallable(catchClause) ?: return
            if (!isSuspendingContext(callable)) return
            if (firstStatementReRaisesCancellation(catchClause)) return

            report(
                Finding(
                    Entity.from(catchClause),
                    "This catch runs in a suspending context and can swallow the coroutine's " +
                        "cancellation. Rethrow the exception if it is a CancellationException, or " +
                        "call coroutineContext.ensureActive(), before any other handling.",
                ),
            )
        }
    }

    private fun KaSession.isGenericThrowableType(typeReference: KtTypeReference): Boolean =
        typeReference.type.isAnyOf(GENERIC_THROWABLES)

    private fun KaSession.isCancellationExceptionType(typeReference: KtTypeReference): Boolean =
        typeReference.type.isAnyOf(CANCELLATION_EXCEPTIONS)

    private fun KaType.isAnyOf(fqNames: Set<String>): Boolean =
        (this as? KaClassType)?.classId?.asFqNameString() in fqNames

    private fun nearestEnclosingCallable(catchClause: KtCatchClause): KtElement? =
        catchClause.getParentOfTypes(
            strict = true,
            KtNamedFunction::class.java,
            KtLambdaExpression::class.java,
        )

    private fun KaSession.isSuspendingContext(callable: KtElement): Boolean =
        when (callable) {
            is KtNamedFunction -> {
                if (callable.name != null) {
                    (callable.symbol as? KaNamedFunctionSymbol)?.isSuspend == true
                } else {
                    // An anonymous function is an expression whose resolved type carries
                    // its suspend-ness.
                    callable.expressionType?.isSuspendFunctionType == true
                }
            }

            is KtLambdaExpression -> {
                callable.expressionType?.isSuspendFunctionType == true ||
                    isSuspendLambdaArgument(callable)
            }

            else -> {
                false
            }
        }

    private fun KaSession.isSuspendLambdaArgument(lambda: KtLambdaExpression): Boolean {
        val argumentExpression =
            (lambda.parent as? KtValueArgument)?.getArgumentExpression() ?: lambda
        val call = lambda.getParentOfType<KtCallExpression>(strict = true) ?: return false
        val parameterType =
            call
                .resolveToCall()
                ?.singleFunctionCallOrNull()
                ?.argumentMapping
                ?.get(argumentExpression)
                ?.returnType ?: return false
        return parameterType.isSuspendFunctionType
    }

    private fun KaSession.firstStatementReRaisesCancellation(catchClause: KtCatchClause): Boolean {
        val body = catchClause.catchBody ?: return false
        val firstStatement =
            (body as? KtBlockExpression)?.statements?.firstOrNull() ?: return false
        val parameterName = catchClause.catchParameter?.name ?: return false
        return isEnsureActiveCall(firstStatement) ||
            isRethrowIfCancellation(firstStatement, parameterName) ||
            isRethrowWhenCancellation(firstStatement, parameterName)
    }

    private fun KaSession.isEnsureActiveCall(statement: KtExpression): Boolean {
        val call =
            when (statement) {
                is KtDotQualifiedExpression -> statement.selectorExpression as? KtCallExpression
                is KtCallExpression -> statement
                else -> null
            } ?: return false
        val callee =
            call
                .resolveToCall()
                ?.singleFunctionCallOrNull()
                ?.symbol
                ?.callableId
                ?.asSingleFqName()
                ?.asString()
        return callee == ENSURE_ACTIVE
    }

    private fun KaSession.isRethrowIfCancellation(statement: KtExpression, parameterName: String): Boolean {
        val ifExpression = statement as? KtIfExpression ?: return false
        val condition = ifExpression.condition as? KtIsExpression ?: return false
        if (condition.isNegated) return false
        if (!condition.leftHandSide.refersTo(parameterName)) return false
        val checkedType = condition.typeReference ?: return false
        if (!isCancellationExceptionType(checkedType)) return false
        return ifExpression.then.rethrows(parameterName)
    }

    private fun KaSession.isRethrowWhenCancellation(statement: KtExpression, parameterName: String): Boolean {
        val whenExpression = statement as? KtWhenExpression ?: return false
        if (whenExpression.subjectExpression?.refersTo(parameterName) != true) return false
        return whenExpression.entries.any { entry ->
            entry.conditions
                .filterIsInstance<KtWhenConditionIsPattern>()
                .any { condition ->
                    !condition.isNegated &&
                        condition.typeReference?.let { isCancellationExceptionType(it) } == true
                } &&
                entry.expression.rethrows(parameterName)
        }
    }

    private fun KtExpression?.rethrows(parameterName: String): Boolean {
        val statement = ((this as? KtBlockExpression)?.statements?.singleOrNull()) ?: this
        val thrown = (statement as? KtThrowExpression)?.thrownExpression ?: return false
        return thrown.refersTo(parameterName)
    }

    private fun KtExpression.refersTo(parameterName: String): Boolean =
        (this as? KtNameReferenceExpression)?.getReferencedName() == parameterName

    private companion object {
        const val ENSURE_ACTIVE = "kotlinx.coroutines.ensureActive"

        // kotlin.Exception and kotlin.RuntimeException are JVM typealiases whose
        // resolved class ids are the java.lang types; both spellings stay listed in
        // case the Analysis API surfaces the abbreviation instead of the expansion.
        val GENERIC_THROWABLES =
            setOf(
                "kotlin.Throwable",
                "java.lang.Throwable",
                "kotlin.Exception",
                "java.lang.Exception",
                "kotlin.RuntimeException",
                "java.lang.RuntimeException",
            )

        // kotlinx.coroutines.CancellationException and the stdlib's
        // kotlin.coroutines.cancellation.CancellationException both alias the JDK
        // class, so the expansion is what resolution returns.
        val CANCELLATION_EXCEPTIONS =
            setOf(
                "java.util.concurrent.CancellationException",
                "kotlin.coroutines.cancellation.CancellationException",
                "kotlinx.coroutines.CancellationException",
            )
    }
}
