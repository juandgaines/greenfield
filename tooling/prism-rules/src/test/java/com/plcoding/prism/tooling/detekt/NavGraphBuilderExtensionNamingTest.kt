package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class NavGraphBuilderExtensionNamingTest {
    private val rule = NavGraphBuilderExtensionNaming(Config.empty)

    @Test
    fun `reports a NavGraphBuilder extension not named Graph`() {
        val findings = rule.lint("fun NavGraphBuilder.authScreens(navController: NavController) {}")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("authScreens")
    }

    @Test
    fun `reports a qualified NavGraphBuilder receiver`() {
        val findings = rule.lint("fun androidx.navigation.NavGraphBuilder.authFlow() {}")

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not report the Graph naming the repo uses`() {
        val code =
            """
            fun NavGraphBuilder.authGraph(navController: NavController, onSignedIn: () -> Unit) {}

            fun NavGraphBuilder.homeShellGraph(onOpenFile: (String) -> Unit) {}
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report extensions on other receivers`() {
        assertThat(rule.lint("fun NavController.navigateToAuth() {}")).isEmpty()
    }

    @Test
    fun `does not report plain functions`() {
        assertThat(rule.lint("fun buildRoutes() {}")).isEmpty()
    }
}
