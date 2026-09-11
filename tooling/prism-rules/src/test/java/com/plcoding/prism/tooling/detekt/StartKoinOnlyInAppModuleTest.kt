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

class StartKoinOnlyInAppModuleTest {
    private val rule = StartKoinOnlyInAppModule(Config.empty)

    @Test
    fun `reports a startKoin call`() {
        val code =
            """
            fun boot() {
                startKoin {
                    modules(featureAuthDataModule)
                }
            }
            """.trimIndent()
        val findings = rule.lint(code)

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("Application class")
    }

    @Test
    fun `does not report other Koin calls`() {
        assertThat(rule.lint("fun boot() = stopKoin()")).isEmpty()
    }

    @Test
    fun `fires in a feature module and is excluded in app main and test sources`(
        @TempDir root: Path,
    ) {
        val violation = "fun boot() { startKoin { } }"
        val scoped =
            StartKoinOnlyInAppModule(
                TestConfig(
                    "active" to true,
                    "excludes" to listOf("**/test/**", "**/androidTest/**", "**/app/src/main/**"),
                ),
            )
        val appApplication = "app/src/main/java/com/example/app/ExampleApp.kt"

        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, appApplication, violation)).isEmpty()
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
