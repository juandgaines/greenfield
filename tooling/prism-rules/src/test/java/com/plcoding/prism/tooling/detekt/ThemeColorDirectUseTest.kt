package com.plcoding.prism.tooling.detekt

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ThemeColorDirectUseTest {
    // Invented fixture data, and configured explicitly: the rule refuses to run
    // without a palette name, because guessing one is how it used to pass over
    // code it never read.
    private val rule = ThemeColorDirectUse(TestConfig("paletteObject" to "AppColors"))

    @Test
    fun `reports a palette reference`() {
        val findings = rule.lint("fun tint() = AppColors.WarmAmber")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("WarmAmber")
    }

    @Test
    fun `does not report a colour read through the scheme`() {
        assertThat(rule.lint("fun tint() = MaterialTheme.colorScheme.error")).isEmpty()
    }

    @Test
    fun `does not report a colour read through the extended tokens`() {
        assertThat(rule.lint("fun tint() = MaterialTheme.appExtendedColors.warning")).isEmpty()
    }

    @Test
    fun `fires outside the theme package and is excluded inside it`(
        @TempDir root: Path,
    ) {
        val violation = "fun tint() = AppColors.WarmAmber"
        val scoped =
            ThemeColorDirectUse(
                TestConfig(
                    "active" to true,
                    "excludes" to listOf("**/test/**", "**/androidTest/**", "**/designsystem/theme/**"),
                    "paletteObject" to "AppColors",
                ),
            )
        val insideTheme = "core/design-system/src/main/java/com/example/app/core/designsystem/theme/Color.kt"
        val outsideTheme = "core/design-system/src/main/java/com/example/app/core/designsystem/components/Badge.kt"

        assertThat(scoped.lintAt(root, outsideTheme, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, insideTheme, violation)).isEmpty()
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }

    @Test
    fun `refuses to run when the palette object is not configured`() {
        val unconfigured = ThemeColorDirectUse(Config.empty)

        assertFailure {
            unconfigured.lint("fun tint() = AppColors.WarmAmber")
        }.isInstanceOf(IllegalStateException::class)
    }

    @Test
    fun `refuses a placeholder name, not only a blank one`() {
        // THE MEASURED FAILURE. PRISM's convention is that a repository with no
        // palette declaration switches the group off and gives the name the
        // inert value 'none'. An install wrote 'none' and left the rules ON.
        // The old guard was isNotBlank(), and 'none' is not blank -- so the rule
        // ran, compared every receiver against the literal string "none", never
        // matched, and reported success over five files that violated it.
        listOf("none", "None", "NONE", " none ", "n/a", "TODO", "tbd", "unknown")
            .forEach { placeholder ->
                val misconfigured =
                    ThemeColorDirectUse(TestConfig("paletteObject" to placeholder))

                assertFailure {
                    misconfigured.lint("fun tint() = AppColors.WarmAmber")
                }.isInstanceOf(IllegalStateException::class)
            }
    }

    @Test
    fun `the refusal names the switch that is the other legal answer`() {
        val misconfigured = ThemeColorDirectUse(TestConfig("paletteObject" to "none"))

        assertFailure {
            misconfigured.lint("fun tint() = AppColors.WarmAmber")
        }.messageContains("PALETTE_RULES_ACTIVE")
    }
}
