package com.plcoding.prism.tooling.konsist

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Test

/**
 * Conventions over the test sources themselves.
 *
 * Both rules are deliberately expressed so that test *support* types — robots,
 * harnesses, fakes, fixture builders — fall outside them by construction. An
 * exclusion list would have to be maintained; a selector that only picks up
 * classes actually declaring test functions does not.
 */
class TestConventionRulesTest {
    @Test
    fun `a class declaring test functions is named Test`() {
        val subjects =
            ProjectScope.testFiles
                .flatMap { it.classes(includeNested = true) }
                .filter { klass -> klass.functions().any { it.hasAnnotationWithName(TEST_ANNOTATION) } }

        subjects.assertAllWhereApplicable(
            rule = "test classes are named *Test",
            subject = "test classes",
            requirement =
                "a class that declares @Test functions must be named with a `Test` suffix, " +
                    "so the test-task filters and the coverage tooling can find it by name",
        ) { it.name.endsWith(TEST_SUFFIX) }
    }

    @Test
    fun `a test sits in the same package as the code it exercises`() {
        val productionByName = ProjectScope.productionFiles.groupBy { it.name }

        // Only tests whose subject ships in the same module are in scope. An
        // integration or E2E test has no single production counterpart, and a name
        // that collides across modules — `BreadcrumbTest` covers the design-system
        // component in one module and the domain model in another — is not drift.
        val subjects =
            ProjectScope.testFiles
                .filter { it.name.endsWith(TEST_SUFFIX) }
                .filter { testFile -> subjectsInSameModule(productionByName, testFile).isNotEmpty() }

        subjects.assertAllWhereApplicable(
            rule = "tests mirror their subject's package",
            subject = "test classes",
            requirement =
                "a test must declare the same package as the production file it covers, so " +
                    "the two move together and internal declarations stay reachable",
        ) { testFile ->
            subjectsInSameModule(productionByName, testFile)
                .any { it.packagee?.name == testFile.packagee?.name }
        }
    }

    private fun subjectsInSameModule(
        productionByName: Map<String, List<KoFileDeclaration>>,
        testFile: KoFileDeclaration,
    ): List<KoFileDeclaration> =
        productionByName[testFile.name.removeSuffix(TEST_SUFFIX)]
            .orEmpty()
            .filter { it.moduleName == testFile.moduleName }

    private companion object {
        const val TEST_ANNOTATION = "Test"
        const val TEST_SUFFIX = "Test"
    }
}
