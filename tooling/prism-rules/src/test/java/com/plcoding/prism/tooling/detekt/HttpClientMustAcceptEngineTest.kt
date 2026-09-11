package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class HttpClientMustAcceptEngineTest {
    private val rule = HttpClientMustAcceptEngine(Config.empty)

    @Test
    fun `reports a client built with only a configuration lambda`() {
        val code =
            """
            fun create() =
                HttpClient {
                    install(ContentNegotiation)
                }
            """.trimIndent()
        val findings = rule.lint(code)

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("engine")
    }

    @Test
    fun `reports a client built with no arguments at all`() {
        assertThat(rule.lint("fun create() = HttpClient()")).hasSize(1)
    }

    @Test
    fun `reports a client whose only parenthesized argument is a lambda`() {
        assertThat(rule.lint("fun create() = HttpClient({ install(ContentNegotiation) })")).hasSize(1)
    }

    @Test
    fun `does not report a client built on an engine factory`() {
        val code =
            """
            fun create() =
                HttpClient(CIO) {
                    install(ContentNegotiation)
                }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report a client built on an engine instance without a lambda`() {
        assertThat(rule.lint("fun create(engine: HttpClientEngine) = HttpClient(engine)")).isEmpty()
    }

    @Test
    fun `does not report unrelated calls`() {
        assertThat(rule.lint("fun create() = httpClient { }")).isEmpty()
    }
}
