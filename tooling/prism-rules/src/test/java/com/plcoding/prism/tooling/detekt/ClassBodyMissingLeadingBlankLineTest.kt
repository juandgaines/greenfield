package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class ClassBodyMissingLeadingBlankLineTest {
    private val rule = ClassBodyMissingLeadingBlankLine(Config.empty)

    @Test
    fun `reports a class body starting directly with a member`() {
        val code =
            """
            class Store(private val cache: Cache) {
                private val entries = mutableListOf<String>()

                fun size() = entries.size
            }
            """.trimIndent()

        assertThat(rule.lint(code)).hasSize(1)
    }

    @Test
    fun `does not report a class body with a leading blank line`() {
        val code =
            """
            class Store(private val cache: Cache) {

                private val entries = mutableListOf<String>()

                fun size() = entries.size
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `reports an interface body without a leading blank line`() {
        val code =
            """
            interface Store {
                fun size(): Int
            }
            """.trimIndent()

        assertThat(rule.lint(code)).hasSize(1)
    }

    @Test
    fun `reports an object body without a leading blank line`() {
        val code =
            """
            object Store {
                val entries = mutableListOf<String>()
            }
            """.trimIndent()

        assertThat(rule.lint(code)).hasSize(1)
    }

    @Test
    fun `does not report a companion object body`() {
        val code =
            """
            class Store {

                companion object {
                    const val LIMIT = 10
                }
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report an enum class`() {
        val code =
            """
            enum class SortOrder {
                Ascending,
                Descending,
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report a single-line body`() {
        assertThat(rule.lint("class Wrapper { val value = 1 }")).isEmpty()
    }

    @Test
    fun `reports a comment directly after the brace without a blank line`() {
        val code =
            """
            class Store {
                // entries live here
                val entries = mutableListOf<String>()
            }
            """.trimIndent()

        assertThat(rule.lint(code)).hasSize(1)
    }

    @Test
    fun `does not report a comment preceded by a blank line`() {
        val code =
            """
            class Store {

                // entries live here
                val entries = mutableListOf<String>()
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }
}
