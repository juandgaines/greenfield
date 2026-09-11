package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Reports a jupiter import in an instrumented test.
 *
 * `AndroidJUnitRunner` only discovers JUnit4 tests. A jupiter `@Test` in
 * `src/androidTest` compiles, is never run, and reports the suite green while
 * asserting nothing. Scoping to `src/androidTest` happens in the root
 * `detekt.yml`, not here.
 */
class JUnit5InInstrumentedTest(config: Config) :
    Rule(
        config,
        "Instrumented tests use JUnit4; a jupiter test silently never runs under AndroidJUnitRunner.",
    ) {
    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val imported = importDirective.importedFqName?.asString() ?: return
        if (!imported.startsWith(JUPITER_PREFIX)) return
        report(
            Finding(
                Entity.from(importDirective),
                "Replace '$imported' with its JUnit4 equivalent — a jupiter test silently never " +
                    "runs under AndroidJUnitRunner.",
            ),
        )
    }

    private companion object {
        const val JUPITER_PREFIX = "org.junit.jupiter"
    }
}
