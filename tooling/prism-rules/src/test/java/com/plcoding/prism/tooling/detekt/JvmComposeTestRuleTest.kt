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

class JvmComposeTestRuleTest {
    private val rule = JvmComposeTestRule(Config.empty)

    @Test
    fun `reports createComposeRule`() {
        val findings = rule.lint("class Sample { val composeRule = createComposeRule() }")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("createAndroidComposeRule<ComponentActivity>()")
    }

    @Test
    fun `reports createEmptyComposeRule`() {
        assertThat(rule.lint("class Sample { val composeRule = createEmptyComposeRule() }")).hasSize(1)
    }

    @Test
    fun `does not report createAndroidComposeRule`() {
        assertThat(
            rule.lint("class Sample { val composeRule = createAndroidComposeRule<ComponentActivity>() }"),
        ).isEmpty()
    }

    @Test
    fun `fires under both test trees and stays out of main sources`(
        @TempDir root: Path,
    ) {
        val violation = "class Sample { val composeRule = createComposeRule() }"
        val scoped =
            JvmComposeTestRule(
                TestConfig("active" to true, "includes" to listOf("**/test/**", "**/androidTest/**")),
            )

        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).isEmpty()
    }
}
