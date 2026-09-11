package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class StartForegroundViaServiceCompatTest {
    private val rule = StartForegroundViaServiceCompat(Config.empty)

    @Test
    fun `reports a bare two argument startForeground call`() {
        val findings = rule.lint("fun promote() = startForeground(1, notification)")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("ServiceCompat")
    }

    @Test
    fun `reports a two argument startForeground call on a receiver`() {
        assertThat(rule.lint("fun promote(service: Service) = service.startForeground(1, notification)")).hasSize(1)
    }

    @Test
    fun `does not report a ServiceCompat startForeground call`() {
        val code =
            """
            fun promote(service: Service) =
                ServiceCompat.startForeground(service, 1, notification, type)
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report a fully qualified ServiceCompat call`() {
        val code =
            """
            fun promote(service: Service) =
                androidx.core.app.ServiceCompat.startForeground(service, 1, notification, type)
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report a startForeground call with a different arity`() {
        assertThat(rule.lint("fun promote() = startForeground(1, notification, type)")).isEmpty()
    }

    @Test
    fun `does not report an unrelated call`() {
        assertThat(rule.lint("fun promote() = startService(1, notification)")).isEmpty()
    }
}
