package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Reports a legacy `org.junit.*` import in a JVM unit test.
 *
 * JVM unit tests run on JUnit5. A JUnit4 import either drags in a second test
 * framework or, worse, produces a `@Test` the jupiter engine never discovers.
 * Scoping to `src/test` happens in the root `detekt.yml`, not here.
 */
class JUnit4InJvmUnitTest(config: Config) :
    Rule(
        config,
        "JVM unit tests use JUnit5; legacy org.junit imports do not belong under src/test.",
    ) {
    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val imported = importDirective.importedFqName?.asString() ?: return
        if (!imported.startsWith(LEGACY_JUNIT_PREFIX)) return
        if (JUNIT5_PREFIXES.any { allowedPrefix -> imported.startsWith(allowedPrefix) }) return
        report(
            Finding(
                Entity.from(importDirective),
                "Replace '$imported' with its JUnit5 equivalent — JVM unit tests use JUnit5 " +
                    "(org.junit.jupiter).",
            ),
        )
    }

    private companion object {
        const val LEGACY_JUNIT_PREFIX = "org.junit."
        val JUNIT5_PREFIXES = listOf("org.junit.jupiter", "org.junit.platform")
    }
}
