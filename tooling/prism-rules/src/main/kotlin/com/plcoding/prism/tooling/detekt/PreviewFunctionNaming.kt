package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Reports a preview function whose name does not end in `Preview`.
 *
 * The suffix is what makes a preview recognisable in the file, in the preview
 * pane, and to the tooling that renders previews by name.
 */
class PreviewFunctionNaming(config: Config) :
    Rule(
        config,
        "Preview functions are named *Preview.",
    ) {
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (!function.isPreview()) return
        val name = function.name ?: return
        if (name.endsWith(PREVIEW_SUFFIX)) return
        report(
            Finding(
                Entity.from(function),
                "Rename '$name' to end in 'Preview' so the function reads as the " +
                    "preview it is.",
            ),
        )
    }

    private companion object {
        const val PREVIEW_SUFFIX = "Preview"
    }
}
