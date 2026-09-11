package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class ModifierParameterAfterRequiredParametersTest {
    private val rule = ModifierParameterAfterRequiredParameters(Config.empty)

    @Test
    fun `reports a defaulted parameter before modifier`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun Chip(text: String, enabled: Boolean = true, modifier: Modifier = Modifier) {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("enabled")
    }

    @Test
    fun `does not report defaulted parameters after modifier`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun Chip(text: String, modifier: Modifier = Modifier, enabled: Boolean = true) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a required parameter before modifier`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun Chip(text: String, modifier: Modifier = Modifier) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a defaulted composable slot before modifier`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun Chip(leadingIcon: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a composable without a modifier parameter`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun Chip(text: String, enabled: Boolean = true) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }
}
