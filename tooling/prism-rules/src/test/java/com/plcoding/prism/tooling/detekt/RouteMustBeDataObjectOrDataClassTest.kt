package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class RouteMustBeDataObjectOrDataClassTest {
    private val rule = RouteMustBeDataObjectOrDataClass(Config.empty)

    @Test
    fun `reports a plain object route`() {
        val findings = rule.lint("object HomeRoute")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("data object")
    }

    @Test
    fun `reports a plain class route`() {
        val findings = rule.lint("class DetailRoute(val id: String)")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("data class")
    }

    @Test
    fun `does not report a data object route`() {
        assertThat(rule.lint("data object HomeShellRoute")).isEmpty()
    }

    @Test
    fun `does not report a data class route`() {
        assertThat(rule.lint("data class FilesRoute(val folderId: String?)")).isEmpty()
    }

    @Test
    fun `does not report a sealed route parent`() {
        val code =
            """
            sealed interface AuthRoute {
                data object Onboarding : AuthRoute
            }

            sealed class LegacyRoute
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report interfaces enums or abstract classes`() {
        val code =
            """
            interface FileRoute

            enum class SyncRoute { Wifi, Cellular }

            abstract class BaseRoute
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report declarations not named like a route`() {
        assertThat(rule.lint("class RouteParser")).isEmpty()
    }
}
