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

class SingleLetterIdentifierTest {
    private val rule = SingleLetterIdentifier(Config.empty)

    @Test
    fun `reports a single-letter val`() {
        assertThat(rule.lint("fun go() { val r = fetch() }")).hasSize(1)
    }

    @Test
    fun `reports a single-letter var`() {
        assertThat(rule.lint("fun go() { var a = 0 }")).hasSize(1)
    }

    @Test
    fun `reports a single-letter function parameter`() {
        assertThat(rule.lint("fun compare(a: ByteArray, b: ByteArray) = a === b")).hasSize(2)
    }

    @Test
    fun `reports a single-letter catch parameter`() {
        assertThat(rule.lint("fun go() { try { work() } catch (e: Exception) { report(e) } }")).hasSize(1)
    }

    @Test
    fun `does not report a descriptive name`() {
        assertThat(rule.lint("fun go(rawResponse: String) { val parsedParams = parse(rawResponse) }")).isEmpty()
    }

    @Test
    fun `does not report the implicit lambda parameter`() {
        assertThat(rule.lint("fun go() { items.map { it.name } }")).isEmpty()
    }

    @Test
    fun `does not report an explicit it or underscore parameter`() {
        assertThat(rule.lint("fun go() { items.forEach { it -> use(it) }; pairs.map { (_, value) -> value } }")).isEmpty()
    }

    @Test
    fun `does not report a conventional loop index`() {
        assertThat(rule.lint("fun go() { for (i in 0..9) { for (j in 0..9) { use(i, j) } } }")).isEmpty()
    }

    @Test
    fun `still reports a single-letter loop variable that is not an index name`() {
        assertThat(rule.lint("fun go() { for (c in password) { use(c) } }")).hasSize(1)
    }

    @Test
    fun `fires in a main source and is excluded in test sources`(
        @TempDir root: Path,
    ) {
        val violation = "fun go() { val r = fetch() }"
        val scoped = SingleLetterIdentifier(TestConfig(*MAIN_SOURCES_ONLY))

        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
