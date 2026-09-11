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

class CollectAsStateWithoutLifecycleTest {
    private val rule = CollectAsStateWithoutLifecycle(Config.empty)

    @Test
    fun `reports a collectAsState call`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginRoot(viewModel: LoginViewModel) {
                    val state by viewModel.state.collectAsState()
                }
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("collectAsStateWithLifecycle()")
    }

    @Test
    fun `does not report collectAsStateWithLifecycle`() {
        assertThat(
            rule.lint(
                """
                @Composable
                fun LoginRoot(viewModel: LoginViewModel) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                }
                """.trimIndent(),
            ),
        ).isEmpty()
    }

    @Test
    fun `does not report an unrelated collect call`() {
        assertThat(rule.lint("suspend fun drain() = upstream.collect { handle(it) }")).isEmpty()
    }

    @Test
    fun `fires in a main source and is excluded in test sources`(
        @TempDir root: Path,
    ) {
        val violation = "@Composable fun Root() { val state by flow.collectAsState() }"
        val scoped = CollectAsStateWithoutLifecycle(TestConfig(*MAIN_SOURCES_ONLY))

        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
