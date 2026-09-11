package com.plcoding.prism.tooling.detekt

import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

private const val COMPOSABLE = "Composable"
private const val PREVIEW = "Preview"

/** Whether the function carries a `@Composable` annotation. */
internal fun KtNamedFunction.isComposable(): Boolean = annotationEntries.any { it.shortName?.asString() == COMPOSABLE }

/**
 * Whether the function is a preview: annotated `@Preview` or with a
 * multipreview whose name starts with `Preview` (`PreviewLightDark`,
 * `PreviewScreenSizes`, ...).
 */
internal fun KtNamedFunction.isPreview(): Boolean =
    annotationEntries.any { it.shortName?.asString()?.startsWith(PREVIEW) == true }

/** Whether the parameter is a `@Composable` function-type (slot) parameter. */
internal fun KtParameter.isComposableSlot(): Boolean {
    val type = typeReference ?: return false
    val hasFunctionType = type.collectDescendantsOfType<KtFunctionType>().isNotEmpty()
    if (!hasFunctionType) return false
    return type
        .collectDescendantsOfType<KtAnnotationEntry>()
        .any { it.shortName?.asString() == COMPOSABLE }
}
