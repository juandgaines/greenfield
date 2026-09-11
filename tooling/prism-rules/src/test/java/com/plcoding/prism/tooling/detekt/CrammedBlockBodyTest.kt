package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class CrammedBlockBodyTest {
    private val rule = CrammedBlockBody(Config.empty)

    @Test
    fun `reports six consecutive statements without a blank line`() {
        val code =
            """
            fun go() {
                val first = load()
                val second = load()
                val third = load()
                val fourth = load()
                val fifth = load()
                val sixth = load()
            }
            """.trimIndent()

        assertThat(rule.lint(code)).hasSize(1)
    }

    @Test
    fun `does not report five consecutive statements`() {
        val code =
            """
            fun go() {
                val first = load()
                val second = load()
                val third = load()
                val fourth = load()
                val fifth = load()
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report six statements split by a blank line`() {
        val code =
            """
            fun go() {
                val first = load()
                val second = load()
                val third = load()

                val fourth = load()
                val fifth = load()
                val sixth = load()
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `reports a long wall once per run, not once per statement`() {
        val code =
            """
            fun go() {
                val a1 = load()
                val a2 = load()
                val a3 = load()
                val a4 = load()
                val a5 = load()
                val a6 = load()
                val a7 = load()
                val a8 = load()
            }
            """.trimIndent()

        assertThat(rule.lint(code)).hasSize(1)
    }

    @Test
    fun `reports a crammed lambda body`() {
        val code =
            """
            fun go() {
                scope.launch {
                    val first = load()
                    val second = load()
                    val third = load()
                    val fourth = load()
                    val fifth = load()
                    val sixth = load()
                }
            }
            """.trimIndent()

        assertThat(rule.lint(code)).hasSize(1)
    }

    @Test
    fun `a blank line before a comment breaks the run`() {
        val code =
            """
            fun go() {
                val first = load()
                val second = load()
                val third = load()

                // the next step
                val fourth = load()
                val fifth = load()
                val sixth = load()
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `a comment without a blank line does not break the run`() {
        val code =
            """
            fun go() {
                val first = load()
                val second = load()
                val third = load()
                // crammed comment
                val fourth = load()
                val fifth = load()
                val sixth = load()
            }
            """.trimIndent()

        assertThat(rule.lint(code)).hasSize(1)
    }

    @Test
    fun `the run length is configurable`() {
        val configured = CrammedBlockBody(TestConfig("maxRunLength" to 2))
        val code =
            """
            fun go() {
                val first = load()
                val second = load()
                val third = load()
            }
            """.trimIndent()

        assertThat(configured.lint(code)).hasSize(1)
    }

    @Test
    fun `the message tells the agent to split by semantic meaning`() {
        val code =
            """
            fun go() {
                val first = load()
                val second = load()
                val third = load()
                val fourth = load()
                val fifth = load()
                val sixth = load()
            }
            """.trimIndent()

        val finding = rule.lint(code).single()

        assertThat(finding.message).contains("semantic")
    }
}
