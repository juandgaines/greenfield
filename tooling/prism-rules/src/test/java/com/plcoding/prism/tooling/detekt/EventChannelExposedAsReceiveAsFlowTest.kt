package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class EventChannelExposedAsReceiveAsFlowTest {
    private val rule = EventChannelExposedAsReceiveAsFlow(Config.empty)

    @Test
    fun `reports a public Channel property in a ViewModel`() {
        val findings =
            rule.lint(
                """
                class LoginViewModel : ViewModel() {
                    val events = Channel<LoginEvent>()
                }
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("receiveAsFlow")
    }

    @Test
    fun `reports a property with an explicit Channel type in a ViewModel`() {
        val findings =
            rule.lint(
                """
                class LoginViewModel : ViewModel() {
                    internal val events: Channel<LoginEvent> = buildEventChannel()
                }
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not report a private Channel exposed via receiveAsFlow`() {
        val findings =
            rule.lint(
                """
                class LoginViewModel : ViewModel() {
                    private val eventChannel = Channel<LoginEvent>()
                    val events = eventChannel.receiveAsFlow()
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a Channel initializer chained into receiveAsFlow`() {
        val findings =
            rule.lint(
                """
                class LoginViewModel : ViewModel() {
                    val events = Channel<LoginEvent>().receiveAsFlow()
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a Channel property outside a ViewModel`() {
        val findings =
            rule.lint(
                """
                class EventBus {
                    val events = Channel<AppEvent>()
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a local Channel inside a ViewModel function`() {
        val findings =
            rule.lint(
                """
                class LoginViewModel : ViewModel() {
                    fun drain() {
                        val buffer = Channel<LoginEvent>()
                    }
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }
}
