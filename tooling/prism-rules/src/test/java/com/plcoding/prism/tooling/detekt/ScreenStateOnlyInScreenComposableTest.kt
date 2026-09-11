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

class ScreenStateOnlyInScreenComposableTest {
    private val rule = ScreenStateOnlyInScreenComposable(Config.empty)

    @Test
    fun `reports a child composable taking the screen state`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun NoteListContent(state: NoteListState) {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("NoteListState")
        assertThat(findings.first().message).contains("NoteListContent")
    }

    @Test
    fun `reports a nullable screen state parameter`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun NoteListContent(state: NoteListState?) {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not report the Screen composable itself`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun NoteListScreen(state: NoteListState, onAction: (NoteListAction) -> Unit) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report the Root composable`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun NoteListRoot(state: NoteListState) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a non-composable function`() {
        assertThat(rule.lint("fun mapToUi(state: NoteListState) = state.toString()")).isEmpty()
    }

    @Test
    fun `does not report a Compose-owned state type`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun NoteListContent(listState: LazyListState, sheetState: SheetState) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a preview function`() {
        val findings =
            rule.lint(
                """
                @Preview
                @Composable
                private fun NoteListContentPreview(state: NoteListState = NoteListState()) {}

                @PreviewLightDark
                @Composable
                private fun NoteListContentDarkPreview(state: NoteListState = NoteListState()) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `honors a custom allowlist`() {
        val configured =
            ScreenStateOnlyInScreenComposable(
                TestConfig("allowedStateTypes" to listOf("MyCustomState")),
            )

        val allowed =
            configured.lint(
                """
                @Composable
                fun NoteListContent(custom: MyCustomState) {}
                """.trimIndent(),
            )
        val noLongerAllowed =
            configured.lint(
                """
                @Composable
                fun NoteListContent(listState: LazyListState) {}
                """.trimIndent(),
            )

        assertThat(allowed).isEmpty()
        assertThat(noLongerAllowed).hasSize(1)
    }

    @Test
    fun `fires under presentation sources and is excluded elsewhere`(
        @TempDir root: Path,
    ) {
        val violation =
            """
            @Composable
            fun NoteListContent(state: NoteListState) {}
            """.trimIndent()
        val scoped =
            ScreenStateOnlyInScreenComposable(
                TestConfig(
                    "active" to true,
                    "includes" to listOf("**/presentation/**"),
                    "excludes" to listOf("**/test/**", "**/androidTest/**"),
                ),
            )
        val domainPath = "feature/auth/domain/src/main/java/com/example/app/Sample.kt"

        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, domainPath, violation)).isEmpty()
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
