package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class ObserveAsEventsOnlyInRootTest {
    private val rule = ObserveAsEventsOnlyInRoot(Config.empty)

    @Test
    fun `reports an ObserveAsEvents call outside a Root`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginScreen(events: Flow<LoginEvent>) {
                    ObserveAsEvents(events) { event -> handle(event) }
                }
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("Root")
    }

    @Test
    fun `does not report an ObserveAsEvents call in a Root`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginRoot(viewModel: LoginViewModel = koinViewModel()) {
                    ObserveAsEvents(viewModel.events) { event -> handle(event) }
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report an ObserveAsEvents call in an activity setContent block`() {
        val findings =
            rule.lint(
                """
                class MainActivity : ComponentActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        setContent {
                            ObserveAsEvents(mainViewModel.events) { event -> handle(event) }
                        }
                    }
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report other calls outside a Root`() {
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
