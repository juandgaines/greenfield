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

class OneShotFlowBuilderTest {
    private val rule = OneShotFlowBuilder(Config.empty)

    @Test
    fun `reports a flow builder whose body is a single emit`() {
        val findings = rule.lint("fun load() = flow { emit(fetch()) }")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("suspend function")
    }

    @Test
    fun `reports a single emit passed as a parenthesized lambda`() {
        assertThat(rule.lint("fun load() = flow({ emit(fetch()) })")).hasSize(1)
    }

    @Test
    fun `does not report a flow with a loop`() {
        assertThat(
            rule.lint(
                """
                fun ticks() = flow {
                    while (true) {
                        emit(now())
                    }
                }
                """.trimIndent(),
            ),
        ).isEmpty()
    }

    @Test
    fun `does not report a flow with a conditional emit`() {
        assertThat(
            rule.lint(
                """
                fun load() = flow {
                    if (cache.isFresh()) emit(cache.read())
                }
                """.trimIndent(),
            ),
        ).isEmpty()
    }

    @Test
    fun `does not report a flow with additional statements`() {
        assertThat(
            rule.lint(
                """
                fun load() = flow {
                    emit(Resource.Loading)
                    emit(fetch())
                }
                """.trimIndent(),
            ),
        ).isEmpty()
    }

    @Test
    fun `does not report a flow whose single statement is not emit`() {
        assertThat(rule.lint("fun load() = flow { emitAll(upstream) }")).isEmpty()
    }

    @Test
    fun `does not report an unrelated builder with a single emit-like call`() {
        assertThat(rule.lint("fun load() = channelFlow { send(fetch()) }")).isEmpty()
    }

    @Test
    fun `fires in a main source and is excluded in test sources`(
        @TempDir root: Path,
    ) {
        val violation = "fun load() = flow { emit(fetch()) }"
        val scoped = OneShotFlowBuilder(TestConfig(*MAIN_SOURCES_ONLY))

        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
