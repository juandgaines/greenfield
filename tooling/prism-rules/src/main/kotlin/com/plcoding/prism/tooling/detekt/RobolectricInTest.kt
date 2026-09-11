package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Reports a Robolectric import in a test.
 *
 * Anything that needs an Android runtime runs as an instrumented test on a
 * real device or emulator; Robolectric's simulated framework is not part of
 * this project's test stack.
 */
class RobolectricInTest(config: Config) :
    Rule(
        config,
        "Robolectric is forbidden; Android-dependent tests run instrumented on a device.",
    ) {
    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val imported = importDirective.importedFqName?.asString() ?: return
        if (!imported.startsWith(ROBOLECTRIC_PREFIX)) return
        report(
            Finding(
                Entity.from(importDirective),
                "Drop '$imported' and move this test to src/androidTest — Android-dependent " +
                    "tests run instrumented, not under Robolectric.",
            ),
        )
    }

    private companion object {
        const val ROBOLECTRIC_PREFIX = "org.robolectric"
    }
}
