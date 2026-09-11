package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class KoinModuleNamingTest {
    private val rule = KoinModuleNaming(Config.empty)

    @Test
    fun `reports a module property without the Module suffix`() {
        val findings = rule.lint("val authDi = module { }")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("authDi")
    }

    @Test
    fun `reports a module property named just module`() {
        assertThat(rule.lint("val moduleThing = module { }")).hasSize(1)
    }

    @Test
    fun `reports a module property starting with an uppercase letter`() {
        assertThat(rule.lint("val AuthDataModule = module { }")).hasSize(1)
    }

    @Test
    fun `does not report a conventionally named module`() {
        val code =
            """
            val featureAuthDataModule =
                module {
                    singleOf(::AuthApiDataSource)
                }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report a non-module property`() {
        assertThat(rule.lint("val retryCount = compute { }")).isEmpty()
    }

    @Test
    fun `does not report a module call without a lambda`() {
        assertThat(rule.lint("val holder = module(definition)")).isEmpty()
    }

    @Test
    fun `does not report a member property holding a module`() {
        val code =
            """
            class Harness {
                private val testOverrides = module { }
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }
}
