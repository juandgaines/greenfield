package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class PreviewFunctionNamingTest {
    private val rule = PreviewFunctionNaming(Config.empty)

    @Test
    fun `reports a preview not named with the suffix`() {
        val findings =
            rule.lint(
                """
                @Preview
                @Composable
                private fun ChipDemo() {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("ChipDemo")
    }

    @Test
    fun `does not report a preview named with the suffix`() {
        val findings =
            rule.lint(
                """
                @Preview
                @Composable
                private fun ChipPreview() {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a non-preview composable of any name`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun Chip(modifier: Modifier = Modifier) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }
}
