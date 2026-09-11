package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class KoinViewModelOnlyInRootTest {
    private val rule = KoinViewModelOnlyInRoot(Config.empty)

    @Test
    fun `reports a koinViewModel call outside a Root`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginScreen() {
                    val viewModel = koinViewModel<LoginViewModel>()
                }
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("koinViewModel")
    }

    @Test
    fun `reports a viewModel call outside a Root`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginScreen() {
                    val viewModel = viewModel<LoginViewModel>()
                }
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not report a koinViewModel call in a Root body`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginRoot() {
                    val viewModel = koinViewModel<LoginViewModel>()
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a koinViewModel default parameter of a Root`() {
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
    fun `does not report a koinViewModel call inside a lambda in a Root`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginRoot() {
                    Wrapper {
                        val viewModel = koinViewModel<LoginViewModel>()
                    }
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report the activity property delegate`() {
        val findings =
            rule.lint(
                """
                class MainActivity : ComponentActivity() {
                    private val mainViewModel: MainViewModel by viewModel()
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a call outside any composable function`() {
        val findings =
            rule.lint(
                """
                class MainActivity : ComponentActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        setContent {
                            val viewModel = viewModel<MainViewModel>()
                        }
                    }
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report unrelated calls`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginScreen(state: LoginState, onAction: (LoginAction) -> Unit) {
                    Text(state.title)
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }
}
