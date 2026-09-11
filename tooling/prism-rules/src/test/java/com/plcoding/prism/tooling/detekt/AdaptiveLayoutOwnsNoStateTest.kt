package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class AdaptiveLayoutOwnsNoStateTest {
    private val rule = AdaptiveLayoutOwnsNoState(Config.empty)

    @Test
    fun `reports mutableStateOf inside an adaptive layout`() {
        val code =
            """
            @Composable
            fun AppAdaptiveFormLayout(content: @Composable () -> Unit) {
                val expanded = remember { mutableStateOf(false) }
            }
            """.trimIndent()

        val findings = rule.lint(code)

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("mutableStateOf")
    }

    @Test
    fun `reports rememberSaveable inside an adaptive layout`() {
        val code =
            """
            @Composable
            fun AppAdaptiveAuthLayout(content: @Composable () -> Unit) {
                val selectedTab = rememberSaveable { mutableIntStateOf(0) }
            }
            """.trimIndent()

        assertThat(rule.lint(code)).hasSize(2)
    }

    @Test
    fun `reports a remember-state factory that is not sanctioned`() {
        val code =
            """
            @Composable
            fun AppAdaptiveSheetLayout(content: @Composable () -> Unit) {
                val sheetState = rememberModalBottomSheetState()
            }
            """.trimIndent()

        val findings = rule.lint(code)

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("rememberModalBottomSheetState")
    }

    @Test
    fun `does not report a sanctioned scroll state`() {
        val code =
            """
            @Composable
            fun AppAdaptiveAuthLayout(content: @Composable ColumnScope.() -> Unit) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    content()
                }
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report state in a composable that is not an adaptive layout`() {
        val code =
            """
            @Composable
            fun SignInScreen(state: SignInState) {
                val visible = remember { mutableStateOf(false) }
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report a non-composable function named like a layout`() {
        val code =
            """
            fun describeAdaptiveLayout(): String {
                val cache = mutableStateOf("unused")
                return cache.value
            }
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `honors a custom allowlist`() {
        val customRule =
            AdaptiveLayoutOwnsNoState(
                TestConfig("allowedStateFactories" to listOf("rememberAppPaneState")),
            )
        val allowed =
            """
            @Composable
            fun AppAdaptivePaneLayout(content: @Composable () -> Unit) {
                val paneState = rememberAppPaneState()
            }
            """.trimIndent()
        val noLongerAllowed =
            """
            @Composable
            fun AppAdaptivePaneLayout(content: @Composable () -> Unit) {
                val scrollState = rememberScrollState()
            }
            """.trimIndent()

        assertThat(customRule.lint(allowed)).isEmpty()
        assertThat(customRule.lint(noLongerAllowed)).hasSize(1)
    }
}
