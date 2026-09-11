package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NoConsoleLoggingTest {
    private val rule = NoConsoleLogging(Config.empty)

    @Test
    fun `reports a println`() {
        assertThat(rule.lint("""fun go() { println("token=${'$'}token") }""")).hasSize(1)
    }

    @Test
    fun `reports a print`() {
        assertThat(rule.lint("""fun go() { print("hello") }""")).hasSize(1)
    }

    @Test
    fun `reports an android util Log call`() {
        assertThat(rule.lint("""fun go() { Log.d("tag", "message") }""")).hasSize(1)
    }

    @Test
    fun `reports a fully qualified Log call`() {
        assertThat(rule.lint("""fun go() { android.util.Log.e("tag", "message") }""")).hasSize(1)
    }

    @Test
    fun `does not report the project logger`() {
        assertThat(rule.lint("""fun go() { logger.log("message") }""")).isEmpty()
    }

    @Test
    fun `does not report an unrelated single-letter call on another receiver`() {
        assertThat(rule.lint("""fun go() { formatter.d(value) }""")).isEmpty()
    }

    @Test
    fun `fires in a main source and is excluded in test sources`(
        @TempDir root: Path,
    ) {
        val violation = """fun go() { println("debug") }"""
        val scoped = NoConsoleLogging(TestConfig(*MAIN_SOURCES_ONLY))

        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
