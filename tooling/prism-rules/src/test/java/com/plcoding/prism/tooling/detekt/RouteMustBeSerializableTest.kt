package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class RouteMustBeSerializableTest {
    private val rule = RouteMustBeSerializable(Config.empty)

    @Test
    fun `reports a data object route without the annotation`() {
        val findings = rule.lint("data object SettingsRoute")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("SettingsRoute")
    }

    @Test
    fun `reports a data class route without the annotation`() {
        assertThat(rule.lint("data class DetailRoute(val id: String)")).hasSize(1)
    }

    @Test
    fun `reports a sealed route parent without the annotation`() {
        val code =
            """
            import kotlinx.serialization.Serializable

            sealed interface AuthRoute {
                @Serializable
                data object Onboarding : AuthRoute
            }
            """.trimIndent()

        val findings = rule.lint(code)

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("AuthRoute")
    }

    @Test
    fun `does not report the annotated route hierarchy the repo uses`() {
        val code =
            """
            import kotlinx.serialization.Serializable

            @Serializable
            sealed interface AuthRoute {
                @Serializable
                data object Onboarding : AuthRoute

                @Serializable
                data class VerifyWaiting(val email: String) : AuthRoute
            }

            @Serializable
            data object HomeShellRoute
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report a fully qualified annotation`() {
        assertThat(rule.lint("@kotlinx.serialization.Serializable data object SettingsRoute")).isEmpty()
    }

    @Test
    fun `does not report a plain interface`() {
        assertThat(rule.lint("interface FileRoute { fun path(): String }")).isEmpty()
    }

    @Test
    fun `does not report an enum`() {
        assertThat(rule.lint("enum class SyncRoute { Wifi, Cellular }")).isEmpty()
    }

    @Test
    fun `does not report declarations not named like a route`() {
        assertThat(rule.lint("data class RoutePlanner(val id: String)")).isEmpty()
    }
}
