package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NonAssertKAssertionTest {
    private val rule = NonAssertKAssertion(Config.empty)

    @Test
    fun `reports a kotlin test import`() {
        val findings = rule.lint("import kotlin.test.assertEquals\n\nclass Sample")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("assertk.assertThat")
    }

    @Test
    fun `reports a JUnit4 Assert import`() {
        assertThat(rule.lint("import org.junit.Assert.assertEquals\n\nclass Sample")).hasSize(1)
    }

    @Test
    fun `reports a jupiter Assertions import`() {
        assertThat(
            rule.lint("import org.junit.jupiter.api.Assertions.assertEquals\n\nclass Sample"),
        ).hasSize(1)
    }

    @Test
    fun `reports a jupiter fail import`() {
        assertThat(rule.lint("import org.junit.jupiter.api.fail\n\nclass Sample")).hasSize(1)
    }

    @Test
    fun `does not report the allowlisted jupiter exception helpers`() {
        val snippet =
            """
            import org.junit.jupiter.api.assertDoesNotThrow
            import org.junit.jupiter.api.assertThrows

            class Sample
            """.trimIndent()

        assertThat(rule.lint(snippet)).isEmpty()
    }

    @Test
    fun `does not report the jupiter Test annotation import`() {
        assertThat(rule.lint("import org.junit.jupiter.api.Test\n\nclass Sample")).isEmpty()
    }

    @Test
    fun `does not report an AssertK import`() {
        assertThat(rule.lint("import assertk.assertThat\n\nclass Sample")).isEmpty()
    }

    @Test
    fun `honors a custom allowlist`() {
        val strict = NonAssertKAssertion(TestConfig("allowedJupiterAssertions" to emptyList<String>()))

        assertThat(strict.lint("import org.junit.jupiter.api.assertThrows\n\nclass Sample")).hasSize(1)
    }

    @Test
    fun `fires under both test trees and stays out of main sources`(
        @TempDir root: Path,
    ) {
        val violation = "import kotlin.test.assertEquals\n\nclass Sample"
        val scoped =
            NonAssertKAssertion(
                TestConfig("active" to true, "includes" to listOf("**/test/**", "**/androidTest/**")),
            )

        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).isEmpty()
    }
}
