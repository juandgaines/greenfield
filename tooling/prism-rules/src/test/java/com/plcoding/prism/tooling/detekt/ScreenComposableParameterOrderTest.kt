package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class ScreenComposableParameterOrderTest {
    private val rule = ScreenComposableParameterOrder(Config.empty)

    @Test
    fun `reports a Screen with swapped state and onAction`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginScreen(onAction: (LoginAction) -> Unit, state: LoginState) {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("state")
    }

    @Test
    fun `reports a Screen with a parameter before state`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginScreen(modifier: Modifier = Modifier, state: LoginState, onAction: (LoginAction) -> Unit) {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not report a Screen with state first and onAction second`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginScreen(
                    state: LoginState,
                    onAction: (LoginAction) -> Unit,
                    modifier: Modifier = Modifier,
                ) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a Screen without both parameters`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun SplashScreen(onFinished: () -> Unit) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a non-Screen composable`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginForm(onAction: (LoginAction) -> Unit, state: LoginState) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a non-composable function ending in Screen`() {
        assertThat(rule.lint("fun logScreen(onAction: () -> Unit, state: String) {}")).isEmpty()
    }
}
