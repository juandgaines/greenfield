package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class ModifierParameterDefaultIsModifierTest {
    private val rule = ModifierParameterDefaultIsModifier(Config.empty)

    @Test
    fun `reports a modifier parameter without a default`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun Chip(text: String, modifier: Modifier) {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("Modifier")
    }

    @Test
    fun `reports a modifier parameter with a different default`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun Chip(text: String, modifier: Modifier = Modifier.fillMaxWidth()) {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not report a modifier defaulting to Modifier`() {
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
    fun `does not report a non-composable function`() {
        val findings = rule.lint("fun chip(modifier: Modifier) {}")

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report an override which cannot declare a default`() {
        val findings =
            rule.lint(
                """
                class ChipHost : Host {
                    @Composable
                    override fun Chip(modifier: Modifier) {}
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a modifier parameter of another modifier type`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun Widget(modifier: GlanceModifier = GlanceModifier) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }
}
