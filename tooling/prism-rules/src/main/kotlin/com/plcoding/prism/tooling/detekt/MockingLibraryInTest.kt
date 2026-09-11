package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Reports a mocking-library import in a test.
 *
 * Tests use hand-written fakes, never mocks: a fake exercises real behavior
 * through the same interface production code uses, while a mock re-states the
 * implementation and rots silently when it changes.
 */
class MockingLibraryInTest(config: Config) :
    Rule(
        config,
        "Tests use hand-written fakes over mocking libraries.",
    ) {
    @Configuration("import prefixes of forbidden mocking libraries")
    private val mockingLibraryPrefixes: List<String> by config(
        listOf("io.mockk", "org.mockito", "org.easymock"),
    )

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        val imported = importDirective.importedFqName?.asString() ?: return
        val matchesLibrary =
            mockingLibraryPrefixes.any { libraryPrefix ->
                imported == libraryPrefix || imported.startsWith("$libraryPrefix.")
            }
        if (!matchesLibrary) return
        report(
            Finding(
                Entity.from(importDirective),
                "Replace this mock ('$imported') with a hand-written fake of the interface — " +
                    "fakes over mocks.",
            ),
        )
    }
}
