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

class ThreadSleepOrEspressoIdleInTestTest {
    private val rule = ThreadSleepOrEspressoIdleInTest(Config.empty)

    @Test
    fun `reports Thread sleep`() {
        val findings = rule.lint("fun waitABit() { Thread.sleep(500) }")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("Thread.sleep")
    }

    @Test
    fun `reports an Espresso onIdle call`() {
        assertThat(rule.lint("fun sync() { Espresso.onIdle() }")).hasSize(1)
    }

    @Test
    fun `reports an Espresso onIdle reference`() {
        assertThat(rule.lint("fun sync() = Espresso.onIdle")).hasSize(1)
    }

    @Test
    fun `reports a static Espresso onIdle import`() {
        assertThat(rule.lint("import androidx.test.espresso.Espresso.onIdle\n\nclass Sample")).hasSize(1)
    }

    @Test
    fun `does not report other Thread members`() {
        assertThat(rule.lint("fun name() = Thread.currentThread()")).isEmpty()
    }

    @Test
    fun `does not report other Espresso members`() {
        assertThat(rule.lint("import androidx.test.espresso.Espresso.onView\n\nfun look() { Espresso.onView(matcher) }")).isEmpty()
    }

    @Test
    fun `does not report a sleep call on another receiver`() {
        assertThat(rule.lint("fun rest() { robot.sleep(1) }")).isEmpty()
    }

    @Test
    fun `fires under both test trees and stays out of main sources`(
        @TempDir root: Path,
    ) {
        val violation = "fun waitABit() { Thread.sleep(500) }"
        val scoped =
            ThreadSleepOrEspressoIdleInTest(
                TestConfig("active" to true, "includes" to listOf("**/test/**", "**/androidTest/**")),
            )

        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).isEmpty()
    }
}
