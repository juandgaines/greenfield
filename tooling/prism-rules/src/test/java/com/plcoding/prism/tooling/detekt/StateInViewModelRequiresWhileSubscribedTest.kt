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

class StateInViewModelRequiresWhileSubscribedTest {
    private val rule = StateInViewModelRequiresWhileSubscribed(Config.empty)

    @Test
    fun `reports an eager stateIn on viewModelScope`() {
        val findings =
            rule.lint(
                "val state = upstream.stateIn(viewModelScope, SharingStarted.Eagerly, Initial)",
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("SharingStarted.WhileSubscribed(5_000L)")
    }

    @Test
    fun `reports a lazy shareIn on viewModelScope`() {
        val findings =
            rule.lint(
                "val events = upstream.shareIn(viewModelScope, SharingStarted.Lazily)",
            )

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `reports an eager stateIn with named arguments`() {
        val findings =
            rule.lint(
                """
                val state = upstream.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = Initial,
                )
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not report WhileSubscribed on viewModelScope`() {
        assertThat(
            rule.lint(
                "val state = upstream.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), Initial)",
            ),
        ).isEmpty()
    }

    @Test
    fun `does not report an eager stateIn on another scope`() {
        assertThat(
            rule.lint(
                "val state = upstream.stateIn(applicationScope, SharingStarted.Eagerly, Initial)",
            ),
        ).isEmpty()
    }

    @Test
    fun `does not report an unrelated call`() {
        assertThat(rule.lint("val state = cache.load(viewModelScope)")).isEmpty()
    }

    @Test
    fun `honors a custom scope name list`() {
        val configured =
            StateInViewModelRequiresWhileSubscribed(
                TestConfig("viewModelScopeNames" to listOf("screenScope")),
            )

        val findings =
            configured.lint(
                "val state = upstream.stateIn(screenScope, SharingStarted.Eagerly, Initial)",
            )

        assertThat(findings).hasSize(1)
        assertThat(
            configured.lint(
                "val state = upstream.stateIn(viewModelScope, SharingStarted.Eagerly, Initial)",
            ),
        ).isEmpty()
    }

    @Test
    fun `fires in a main source and is excluded in test sources`(
        @TempDir root: Path,
    ) {
        val violation = "val state = upstream.stateIn(viewModelScope, SharingStarted.Eagerly, Initial)"
        val scoped = StateInViewModelRequiresWhileSubscribed(TestConfig(*MAIN_SOURCES_ONLY))

        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
