package com.plcoding.prism.tooling.detekt

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class PreviewMustWrapInThemeTest {
    // The rule takes the theme name from configuration and refuses to run without
    // it, so every case here supplies one. `AppTheme` is invented fixture data:
    // naming a real project's theme composable here is what put the origin
    // repository's design system into a shipped product in the first place.
    private val rule = PreviewMustWrapInTheme(TestConfig(*THEME_CONFIG))

    @Test
    fun `reports a preview without the theme wrapper`() {
        val findings =
            rule.lint(
                """
                @Preview
                @Composable
                private fun ChipPreview() {
                    Chip(text = "Kotlin")
                }
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("AppTheme")
    }

    @Test
    fun `does not report a preview wrapping in the theme`() {
        val findings =
            rule.lint(
                """
                @Preview
                @Composable
                private fun ChipPreview() {
                    AppTheme {
                        Chip(text = "Kotlin")
                    }
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a preview delegating to a same-file helper that themes`() {
        val findings =
            rule.lint(
                """
                @Preview
                @Composable
                private fun ShellPortraitPreview() = ShellChromePreview(useRail = false)

                @Composable
                private fun ShellChromePreview(useRail: Boolean) {
                    AppTheme {
                        Shell(useRail = useRail)
                    }
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `reports a preview delegating to a helper that never themes`() {
        val findings =
            rule.lint(
                """
                @Preview
                @Composable
                private fun ShellPortraitPreview() = ShellChromePreview(useRail = false)

                @Composable
                private fun ShellChromePreview(useRail: Boolean) {
                    Shell(useRail = useRail)
                }
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `honors a custom theme name`() {
        val configured = PreviewMustWrapInTheme(TestConfig("themeName" to "MyTheme"))
        val wrappedInMyTheme =
            """
            @Preview
            @Composable
            private fun ChipPreview() {
                MyTheme {
                    Chip(text = "Kotlin")
                }
            }
            """.trimIndent()
        val wrappedInAppTheme =
            """
            @Preview
            @Composable
            private fun ChipPreview() {
                AppTheme {
                    Chip(text = "Kotlin")
                }
            }
            """.trimIndent()

        assertThat(configured.lint(wrappedInMyTheme)).isEmpty()
        assertThat(configured.lint(wrappedInAppTheme)).hasSize(1)
    }

    @Test
    fun `refuses to run when the theme name is not configured`() {
        // A theme name the repository does not use matches no call, so EVERY
        // preview is reported, each message naming a composable nobody there has
        // heard of. Loud rather than silent, but a rule that flags every preview
        // gets switched off -- and it takes the two silent theme rules beside it.
        // Unconfigured refuses rather than guessing.
        val unconfigured = PreviewMustWrapInTheme(Config.empty)

        val thrown =
            assertFailure {
                unconfigured.lint(
                    """
                    @Preview
                    @Composable
                    private fun ChipPreview() {
                        Chip(text = "Kotlin")
                    }
                    """.trimIndent(),
                )
            }.isInstanceOf(IllegalStateException::class)

        assertThat(thrown).isNotNull()
    }

    private companion object {
        val THEME_CONFIG = arrayOf("themeName" to "AppTheme")
    }
}
