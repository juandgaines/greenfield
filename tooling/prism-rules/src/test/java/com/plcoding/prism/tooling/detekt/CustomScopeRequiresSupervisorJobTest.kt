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

class CustomScopeRequiresSupervisorJobTest {
    private val rule = CustomScopeRequiresSupervisorJob(Config.empty)

    @Test
    fun `reports a scope built from a literal Job`() {
        val findings = rule.lint("val scope = CoroutineScope(Job() + Dispatchers.Main)")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("SupervisorJob()")
    }

    @Test
    fun `reports a scope built from a bare dispatcher`() {
        assertThat(rule.lint("val scope = CoroutineScope(Dispatchers.IO)")).hasSize(1)
    }

    @Test
    fun `reports a scope built from combined bare dispatchers`() {
        assertThat(rule.lint("val scope = CoroutineScope(Dispatchers.IO + Dispatchers.Main)")).hasSize(1)
    }

    @Test
    fun `does not report a scope with a SupervisorJob`() {
        assertThat(
            rule.lint("val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)"),
        ).isEmpty()
    }

    @Test
    fun `does not report a scope built from a passed-in context`() {
        assertThat(
            rule.lint("fun scopeOf(context: CoroutineContext) = CoroutineScope(context)"),
        ).isEmpty()
    }

    @Test
    fun `does not report a scope built from a named job variable`() {
        assertThat(rule.lint("val scope = CoroutineScope(parentJob + Dispatchers.Main)")).isEmpty()
    }

    @Test
    fun `does not report a dispatcher combined with a named context`() {
        assertThat(rule.lint("val scope = CoroutineScope(Dispatchers.IO + errorHandler)")).isEmpty()
    }

    @Test
    fun `fires in a main source and is excluded in test sources`(
        @TempDir root: Path,
    ) {
        val violation = "val scope = CoroutineScope(Job() + Dispatchers.Main)"
        val scoped = CustomScopeRequiresSupervisorJob(TestConfig(*MAIN_SOURCES_ONLY))

        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
