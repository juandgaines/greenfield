package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtParameter

/**
 * Reports a parameter annotated `@PreviewParameter`.
 *
 * A provider hides which states a component is previewed in behind a class the
 * preview pane cannot name. One `@Preview` function per meaningful state keeps
 * every state visible, navigable, and individually renderable.
 */
class NoPreviewParameterAnnotation(config: Config) :
    Rule(
        config,
        "Previews are written one @Preview function per state, never via @PreviewParameter.",
    ) {
    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        if (parameter.annotationEntries.none { it.shortName?.asString() == PREVIEW_PARAMETER }) return
        report(
            Finding(
                Entity.from(parameter),
                "Remove @PreviewParameter from '${parameter.name}' and write a separate " +
                    "@Preview function per state instead.",
            ),
        )
    }

    private companion object {
        const val PREVIEW_PARAMETER = "PreviewParameter"
    }
}
