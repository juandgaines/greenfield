package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class ComposableSlotParameterAfterModifierTest {
    private val rule = ComposableSlotParameterAfterModifier(Config.empty)

    @Test
    fun `reports a defaulted slot before modifier`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun Card(content: @Composable () -> Unit = {}, modifier: Modifier = Modifier) {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("content")
    }

    @Test
    fun `does not report a required slot before modifier`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun ListItem(headlineContent: @Composable () -> Unit, modifier: Modifier = Modifier) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `reports a nullable defaulted slot before modifier`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun Card(leadingIcon: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier) {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not report a slot after modifier`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a plain lambda before modifier`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun Card(onClick: () -> Unit, modifier: Modifier = Modifier) {}
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
                fun Card(content: @Composable () -> Unit) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }
}
