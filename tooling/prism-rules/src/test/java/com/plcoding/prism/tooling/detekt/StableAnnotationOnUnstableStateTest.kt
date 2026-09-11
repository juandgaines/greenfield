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

class StableAnnotationOnUnstableStateTest {
    private val rule = StableAnnotationOnUnstableState(Config.empty)

    @Test
    fun `reports a State class with a List constructor property`() {
        val findings = rule.lint("data class NoteListState(val notes: List<NoteUi>)")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("notes")
    }

    @Test
    fun `reports a State class with an unstable member property`() {
        val findings =
            rule.lint(
                """
                class SelectionState {
                    val selectedIds: MutableSet<String> = mutableSetOf()
                }
                """.trimIndent(),
            )

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `reports a nullable unstable collection property`() {
        val findings = rule.lint("data class FilterState(val tags: Map<String, Int>? = null)")

        assertThat(findings).hasSize(1)
    }

    @Test
    fun `does not report a Stable-annotated State class`() {
        val findings = rule.lint("@Stable data class NoteListState(val notes: List<NoteUi>)")

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report an Immutable-annotated State class`() {
        val findings = rule.lint("@Immutable data class NoteListState(val notes: List<NoteUi>)")

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report kotlinx immutable collection properties`() {
        val findings =
            rule.lint(
                """
                data class NoteListState(
                    val notes: ImmutableList<NoteUi>,
                    val tags: PersistentMap<String, Int>,
                )
                """.trimIndent(),
            )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a State class of scalar properties`() {
        val findings = rule.lint("data class SignInState(val email: String = \"\", val isLoading: Boolean = false)")

        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report a collection-holding class not named State`() {
        val findings = rule.lint("data class NoteListUi(val notes: List<NoteUi>)")

        assertThat(findings).isEmpty()
    }

    @Test
    fun `honors a custom stable type allowlist`() {
        val configured =
            StableAnnotationOnUnstableState(TestConfig("stableCollectionTypes" to listOf("List")))

        assertThat(configured.lint("data class NoteListState(val notes: List<NoteUi>)")).isEmpty()
    }

    @Test
    fun `fires in presentation sources and is excluded elsewhere`(
        @TempDir root: Path,
    ) {
        val violation = "data class NoteListState(val notes: List<NoteUi>)"
        val scoped =
            StableAnnotationOnUnstableState(
                TestConfig(
                    "active" to true,
                    "excludes" to listOf("**/test/**", "**/androidTest/**"),
                    "includes" to listOf("**/presentation/**"),
                ),
            )
        val domainPath = "feature/auth/domain/src/main/java/com/example/app/Sample.kt"

        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, domainPath, violation)).isEmpty()
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
