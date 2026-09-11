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

class RobolectricInTestTest {
    private val rule = RobolectricInTest(Config.empty)

    @Test
    fun `reports a Robolectric runner import`() {
        val findings = rule.lint("import org.robolectric.RobolectricTestRunner\n\nclass Sample")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("src/androidTest")
    }

    @Test
    fun `reports a Robolectric annotation import`() {
        assertThat(rule.lint("import org.robolectric.annotation.Config\n\nclass Sample")).hasSize(1)
    }

    @Test
    fun `does not report an androidx test import`() {
        assertThat(
            rule.lint("import androidx.test.ext.junit.runners.AndroidJUnit4\n\nclass Sample"),
        ).isEmpty()
    }

    @Test
    fun `fires under both test trees and stays out of main sources`(
        @TempDir root: Path,
    ) {
        val violation = "import org.robolectric.RobolectricTestRunner\n\nclass Sample"
        val scoped =
            RobolectricInTest(
                TestConfig("active" to true, "includes" to listOf("**/test/**", "**/androidTest/**")),
            )

        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).isEmpty()
    }
}
