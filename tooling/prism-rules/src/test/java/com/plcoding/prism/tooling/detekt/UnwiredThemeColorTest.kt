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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class UnwiredThemeColorTest {
    // The rule takes both names from configuration and refuses to run without
    // them, so every case here supplies them. `AppColors` is invented fixture
    // data: naming a real project's palette here is what put the origin
    // repository's design system into a shipped product in the first place.
    private val rule = UnwiredThemeColor(TestConfig(*PALETTE_CONFIG))

    @Test
    fun `reports a palette entry no scheme references`() {
        val findings =
            rule.lint(
                """
                object AppColors {
                    val VividSkyBlue = Color(0xFF4F8EF7)
                    val Orphan = Color(0xFF000000)
                }

                val DarkColorScheme = darkColorScheme(primary = AppColors.VividSkyBlue)
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("'Orphan'")
    }

    @Test
    fun `does not report an entry wired into a scheme`() {
        val findings =
            rule.lint(
                """
                object AppColors {
                    val VividSkyBlue = Color(0xFF4F8EF7)
                    val NearWhite = Color(0xFFF2F2F7)
                }

                val DarkColorScheme = darkColorScheme(primary = AppColors.VividSkyBlue)
                val LightColorScheme = lightColorScheme(primary = AppColors.NearWhite)
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report an entry wired as a default on the extended token holder`() {
        val findings =
            rule.lint(
                """
                object AppColors {
                    val WarningAmber = Color(0xFFF5A623)
                }

                data class AppExtendedColors(val warning: Color = AppColors.WarningAmber)
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report anything in a file with no palette object`() {
        assertThat(rule.lint("val DarkColorScheme = darkColorScheme(primary = Color.Blue)")).isEmpty()
    }

    @Test
    fun `reports every entry when the palette is split away from its wiring`() {
        // The rule is single-file by construction: it reads the palette and the wiring
        // calls out of the same file. That is only sound while Color.kt declares the
        // object, both schemes, and the extended-token holder together. If the file is
        // ever split this is what happens — every entry reads as unwired, loudly, rather
        // than the rule quietly passing over code it can no longer see. This test exists
        // to pin that failure mode so the split forces the design to be revisited.
        val findings =
            rule.lint(
                """
                object AppColors {
                    val VividSkyBlue = Color(0xFF4F8EF7)
                    val NearWhite = Color(0xFFF2F2F7)
                }
                """.trimIndent(),
            )

        assertThat(findings).hasSize(2)
    }

    @Test
    fun `fires in a main source and is excluded in test sources`(
        @TempDir root: Path,
    ) {
        val violation = "object AppColors { val Orphan = Color(0xFF000000) }"
        val scoped = UnwiredThemeColor(TestConfig(*MAIN_SOURCES_ONLY, *PALETTE_CONFIG))

        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }

    @Test
    fun `refuses to run when the palette object is not configured`() {
        // The silent pass this rule used to ship. With a hardcoded palette name, a
        // repository whose palette is called anything else matched nothing and the
        // rule reported success over the code it was pointed at. Unconfigured now
        // fails loudly instead of finding nothing quietly.
        val unconfigured = UnwiredThemeColor(Config.empty)

        val thrown =
            assertFailure {
                unconfigured.lint("object AppColors { val Orphan = Color(0xFF000000) }")
            }.isInstanceOf(IllegalStateException::class)

        assertThat(thrown).isNotNull()
    }

    private companion object {
        val PALETTE_CONFIG =
            arrayOf(
                "paletteObject" to "AppColors",
                "extendedTokenHolder" to "AppExtendedColors",
            )
    }
}
