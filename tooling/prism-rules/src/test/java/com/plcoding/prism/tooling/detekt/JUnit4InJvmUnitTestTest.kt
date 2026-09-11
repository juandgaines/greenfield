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

class JUnit4InJvmUnitTestTest {
    private val rule = JUnit4InJvmUnitTest(Config.empty)

    @Test
    fun `reports a JUnit4 Test import`() {
        val findings = rule.lint("import org.junit.Test\n\nclass Sample")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("org.junit.Test")
    }

    @Test
    fun `reports a JUnit4 rule import`() {
        assertThat(rule.lint("import org.junit.Rule\n\nclass Sample")).hasSize(1)
    }

    @Test
    fun `does not report a jupiter import`() {
        assertThat(rule.lint("import org.junit.jupiter.api.Test\n\nclass Sample")).isEmpty()
    }

    @Test
    fun `does not report a platform import`() {
        assertThat(rule.lint("import org.junit.platform.launcher.Launcher\n\nclass Sample")).isEmpty()
    }

    @Test
    fun `does not report an unrelated import`() {
        assertThat(rule.lint("import org.junitpioneer.jupiter.RetryingTest\n\nclass Sample")).isEmpty()
    }

    @Test
    fun `fires under test sources only`(
        @TempDir root: Path,
    ) {
        val violation = "import org.junit.Test\n\nclass Sample"
        val scoped =
            JUnit4InJvmUnitTest(
                TestConfig("active" to true, "includes" to listOf("**/test/**")),
            )

        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).isEmpty()
    }
}
