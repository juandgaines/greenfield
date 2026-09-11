package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class PreviewMustBePrivateTest {
    private val rule = PreviewMustBePrivate(Config.empty)

    @Test
    fun `reports a public preview`() {
        val findings =
            rule.lint(
                """
                @Preview
                @Composable
                fun ChipPreview() {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("ChipPreview")
    }

    @Test
    fun `reports an internal multipreview function`() {
        val findings =
            rule.lint(
                """
                @PreviewLightDark
                @Composable
                internal fun ChipPreview() {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not report a private preview`() {
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
    fun `does not report a public composable that is no preview`() {
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
