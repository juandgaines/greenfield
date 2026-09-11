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

class JUnit5InInstrumentedTestTest {
    private val rule = JUnit5InInstrumentedTest(Config.empty)

    @Test
    fun `reports a jupiter Test import`() {
        val findings = rule.lint("import org.junit.jupiter.api.Test\n\nclass Sample")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("AndroidJUnitRunner")
    }

    @Test
    fun `does not report a JUnit4 import`() {
        assertThat(rule.lint("import org.junit.Test\n\nclass Sample")).isEmpty()
    }

    @Test
    fun `does not report an androidx test import`() {
        assertThat(
            rule.lint("import androidx.test.ext.junit.runners.AndroidJUnit4\n\nclass Sample"),
        ).isEmpty()
    }

    @Test
    fun `fires under instrumentation sources only`(
        @TempDir root: Path,
    ) {
        val violation = "import org.junit.jupiter.api.Test\n\nclass Sample"
        val scoped =
            JUnit5InInstrumentedTest(
                TestConfig("active" to true, "includes" to listOf("**/androidTest/**")),
            )

        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).isEmpty()
    }
}
