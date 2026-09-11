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

class NonAtomicStateFlowAssignmentTest {
    private val rule = NonAtomicStateFlowAssignment(Config.empty)

    @Test
    fun `reports an assignment to a backing flow's value`() {
        val findings =
            rule.lint(
                """
                class ViewModel {
                    private val _state = MutableStateFlow(State())
                    fun onClick() {
                        _state.value = _state.value.copy(isLoading = true)
                    }
                }
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("_state.update")
    }

    @Test
    fun `does not report an atomic update`() {
        val findings =
            rule.lint(
                """
                class ViewModel {
                    private val _state = MutableStateFlow(State())
                    fun onClick() {
                        _state.update { it.copy(isLoading = true) }
                    }
                }
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a value assignment on a non-backing receiver`() {
        val findings = rule.lint("fun update(holder: Holder) { holder.value = 5 }")

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report reading the value`() {
        val findings = rule.lint("fun read(): State = _state.value")

        assertThat(findings).isEmpty()
    }

    @Test
    fun `fires in a main source and is excluded in test sources`(
        @TempDir root: Path,
    ) {
        val violation = "class ViewModel { fun go() { _state.value = 1 } }"
        val scoped = NonAtomicStateFlowAssignment(TestConfig(*MAIN_SOURCES_ONLY))

        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
