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

class SharedFlowRequiresExplicitBufferTest {
    private val rule = SharedFlowRequiresExplicitBuffer(Config.empty)

    @Test
    fun `reports a zero-argument MutableSharedFlow`() {
        val findings = rule.lint("private val events = MutableSharedFlow<Event>()")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("replay")
    }

    @Test
    fun `reports a zero-argument MutableSharedFlow without type arguments`() {
        assertThat(rule.lint("private val events = MutableSharedFlow()")).hasSize(1)
    }

    @Test
    fun `does not report a MutableSharedFlow with an explicit replay`() {
        assertThat(rule.lint("private val events = MutableSharedFlow<Event>(replay = 0)")).isEmpty()
    }

    @Test
    fun `does not report a MutableSharedFlow with a positional buffer`() {
        assertThat(rule.lint("private val events = MutableSharedFlow<Event>(1, 64)")).isEmpty()
    }

    @Test
    fun `does not report other flow constructors`() {
        assertThat(rule.lint("private val state = MutableStateFlow(Initial)")).isEmpty()
    }

    @Test
    fun `fires in a main source and is excluded in test sources`(
        @TempDir root: Path,
    ) {
        val violation = "private val events = MutableSharedFlow<Event>()"
        val scoped = SharedFlowRequiresExplicitBuffer(TestConfig(*MAIN_SOURCES_ONLY))

        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
