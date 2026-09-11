package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class PreferConstructorReferenceKoinDefinitionTest {
    private val rule = PreferConstructorReferenceKoinDefinition(Config.empty)

    private fun inModule(definition: String) =
        """
        val featureNoteDataModule =
            module {
                $definition
            }
        """.trimIndent()

    @Test
    fun `reports a single lambda that only calls the constructor with get`() {
        val findings = rule.lint(inModule("single { OfflineFirstNoteRepository(get(), get()) }"))

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("singleOf(::OfflineFirstNoteRepository)")
    }

    @Test
    fun `reports a factory and a viewModel definition with their own replacements`() {
        val factoryFinding = rule.lint(inModule("factory { NoteMapper(get()) }")).first()
        val viewModelFinding = rule.lint(inModule("viewModel { NoteViewModel(get()) }")).first()

        assertThat(factoryFinding.message).contains("factoryOf(::NoteMapper)")
        assertThat(viewModelFinding.message).contains("viewModelOf(::NoteViewModel)")
    }

    @Test
    fun `reports a zero-argument constructor definition`() {
        assertThat(rule.lint(inModule("single { Clock() }"))).hasSize(1)
    }

    @Test
    fun `does not report named constructor arguments`() {
        assertThat(
            rule.lint(inModule("single { SessionLockObserver(authSession = get(), scope = get()) }")),
        ).isEmpty()
    }

    @Test
    fun `does not report a get with a type argument or arguments`() {
        assertThat(rule.lint(inModule("single { NoteMapper(get<Clock>()) }"))).isEmpty()
        assertThat(rule.lint(inModule("single { NoteMapper(get(named(\"io\"))) }"))).isEmpty()
    }

    @Test
    fun `does not report a definition with a type argument`() {
        assertThat(
            rule.lint(inModule("single<NoteRepository> { OfflineFirstNoteRepository(get()) }")),
        ).isEmpty()
    }

    @Test
    fun `does not report a definition chained into bind`() {
        assertThat(
            rule.lint(inModule("single { OfflineFirstNoteRepository(get()) } bind NoteRepository::class")),
        ).isEmpty()
    }

    @Test
    fun `does not report a multi-statement lambda`() {
        val definition =
            """
            single {
                val clock = get<Clock>()
                OfflineFirstNoteRepository(clock)
            }
            """.trimIndent()

        assertThat(rule.lint(inModule(definition))).isEmpty()
    }

    @Test
    fun `does not report a body that is not a constructor-style call`() {
        assertThat(rule.lint(inModule("single { get<NoteDatabase>().noteDao }"))).isEmpty()
        assertThat(rule.lint(inModule("single { buildRepository(get()) }"))).isEmpty()
    }

    @Test
    fun `does not report a matching call outside a module block`() {
        assertThat(rule.lint("fun cache() = single { NoteMapper(get()) }")).isEmpty()
    }
}
