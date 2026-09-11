package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class NoPreviewParameterAnnotationTest {
    private val rule = NoPreviewParameterAnnotation(Config.empty)

    @Test
    fun `reports a PreviewParameter-annotated parameter`() {
        val findings =
            rule.lint(
                """
                @Preview
                @Composable
                private fun NotePreview(@PreviewParameter(NoteProvider::class) note: Note) {}
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("note")
    }

    @Test
    fun `does not report an unannotated parameter`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun NoteCard(note: Note, modifier: Modifier = Modifier) {}
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a parameter with another annotation`() {
        val findings = rule.lint("fun title(@StringRes titleRes: Int) = titleRes")

        assertThat(findings).isEmpty()
    }
}
