package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class RootComposableMustDefaultViewModelTest {
    private val rule = RootComposableMustDefaultViewModel(Config.empty)

    @Test
    fun `reports a Root ViewModel parameter without a default`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginRoot(viewModel: LoginViewModel) {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("koinViewModel")
    }

    @Test
    fun `reports a Root ViewModel parameter with a different default`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginRoot(viewModel: LoginViewModel = viewModel()) {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not report a Root ViewModel parameter defaulting to koinViewModel`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginRoot(viewModel: LoginViewModel = koinViewModel()) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a non-Root composable`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginScreen(viewModel: LoginViewModel) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a non-composable function ending in Root`() {
        assertThat(rule.lint("fun buildRoot(viewModel: LoginViewModel) {}")).isEmpty()
    }

    @Test
    fun `does not report non-ViewModel parameters of a Root`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginRoot(
                    onLoginSuccess: () -> Unit,
                    viewModel: LoginViewModel = koinViewModel(),
                ) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }
}
