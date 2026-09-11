package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class SealedHierarchyOfOnlyObjectsShouldBeEnumTest {
    private val rule = SealedHierarchyOfOnlyObjectsShouldBeEnum(Config.empty)

    @Test
    fun `reports a sealed interface of only data objects`() {
        val findings =
            rule.lint(
                """
                sealed interface Direction {
                    data object Up : Direction
                    data object Down : Direction
                }
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("enum class")
    }

    @Test
    fun `reports a sealed class with same-file plain object subtypes`() {
        val findings =
            rule.lint(
                """
                sealed class Direction

                object Up : Direction()

                object Down : Direction()
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not report when a subtype is a data class`() {
        val findings =
            rule.lint(
                """
                sealed interface LoadState {
                    data object Loading : LoadState
                    data class Loaded(val count: Int) : LoadState
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report when a subtype declares a member`() {
        val findings =
            rule.lint(
                """
                sealed interface Direction {
                    data object Up : Direction {
                        val degrees = 0
                    }
                    data object Down : Direction
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report when the parent declares an abstract member`() {
        val findings =
            rule.lint(
                """
                sealed class Direction {
                    abstract fun degrees(): Int

                    data object Up : Direction() {
                        override fun degrees() = 0
                    }
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report an Action hierarchy`() {
        val findings =
            rule.lint(
                """
                sealed interface LoginAction {
                    data object OnLoginClick : LoginAction
                    data object OnRegisterClick : LoginAction
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report an Event hierarchy`() {
        val findings =
            rule.lint(
                """
                sealed interface LoginEvent {
                    data object NavigateHome : LoginEvent
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a generic hierarchy`() {
        val findings =
            rule.lint(
                """
                sealed interface Holder<T> {
                    data object Empty : Holder<Nothing>
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a hierarchy with no subtype visible in the file`() {
        assertThat(rule.lint("sealed interface Direction")).isEmpty()
    }

    @Test
    fun `honors a custom exempt suffix`() {
        val configured =
            SealedHierarchyOfOnlyObjectsShouldBeEnum(
                TestConfig("exemptSuffixes" to listOf("Command")),
            )
        val commandHierarchy =
            """
            sealed interface SyncCommand {
                data object Start : SyncCommand
                data object Stop : SyncCommand
            }
            """.trimIndent()
        val actionHierarchy =
            """
            sealed interface LoginAction {
                data object OnLoginClick : LoginAction
            }
            """.trimIndent()

        assertThat(configured.lint(commandHierarchy)).isEmpty()
        assertThat(configured.lint(actionHierarchy)).hasSize(1)
    }
}
