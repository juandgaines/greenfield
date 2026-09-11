package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class RootAndScreenInSameFileTest {
    private val rule = RootAndScreenInSameFile(Config.empty)

    @Test
    fun `reports a Root without its Screen in the file`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginRoot(viewModel: LoginViewModel = koinViewModel()) {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("LoginScreen")
    }

    @Test
    fun `reports a Root whose same-named Screen is not composable`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginRoot() {}

                fun LoginScreen() {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `reports a Root paired with a differently named Screen`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginRoot() {}

                @Composable
                fun SignInScreen(state: SignInState, onAction: (SignInAction) -> Unit) {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not report a Root with its Screen in the file`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginRoot(viewModel: LoginViewModel = koinViewModel()) {
                    LoginScreen(state = state, onAction = viewModel::onAction)
                }

                @Composable
                fun LoginScreen(state: LoginState, onAction: (LoginAction) -> Unit) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a non-composable function ending in Root`() {
        assertThat(rule.lint("fun documentRoot(): Node = Node()")).isEmpty()
    }

    @Test
    fun `does not report a composable not ending in Root`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginButton(onClick: () -> Unit) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }
}
