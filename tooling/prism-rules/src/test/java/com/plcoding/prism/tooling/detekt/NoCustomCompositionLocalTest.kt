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

class NoCustomCompositionLocalTest {
    private val rule = NoCustomCompositionLocal(Config.empty)

    @Test
    fun `reports compositionLocalOf`() {
        val findings = rule.lint("val LocalSpacing = compositionLocalOf { Spacing() }")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("compositionLocalOf")
    }

    @Test
    fun `reports staticCompositionLocalOf`() {
        val findings = rule.lint("val LocalElevation = staticCompositionLocalOf { Elevation() }")

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not report reading or providing an existing local`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun Themed(content: @Composable () -> Unit) {
                    val spacing = LocalSpacing.current
                    CompositionLocalProvider(LocalContentColor provides Color.Red, content = content)
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `fires outside the theme package and is excluded inside it`(
        @TempDir root: Path,
    ) {
        val violation = "val LocalSpacing = compositionLocalOf { Spacing() }"
        val scoped =
            NoCustomCompositionLocal(
                TestConfig(
                    "active" to true,
                    "excludes" to listOf("**/test/**", "**/androidTest/**", "**/designsystem/theme/**"),
                ),
            )
        val insideTheme = "core/design-system/src/main/java/com/example/app/core/designsystem/theme/Spacing.kt"
        val outsideTheme = "feature/files/presentation/src/main/java/com/example/app/feature/files/presentation/Locals.kt"

        assertThat(scoped.lintAt(root, outsideTheme, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, insideTheme, violation)).isEmpty()
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
