package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class NoImplSuffixTest {
    private val rule = NoImplSuffix(Config.empty)

    @Test
    fun `reports an Impl-suffixed class`() {
        val findings = rule.lint("class NoteRepositoryImpl : NoteRepository")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("NoteRepositoryImpl")
    }

    @Test
    fun `reports an Impl-suffixed object`() {
        assertThat(rule.lint("object ClockImpl : Clock")).hasSize(1)
    }

    @Test
    fun `reports an Impl-suffixed interface`() {
        assertThat(rule.lint("interface StorageImpl")).hasSize(1)
    }

    @Test
    fun `does not report a class named for what makes it unique`() {
        assertThat(rule.lint("class OfflineFirstNoteRepository : NoteRepository")).isEmpty()
    }

    @Test
    fun `does not report a name merely containing Impl`() {
        assertThat(rule.lint("class ImplementationNotes")).isEmpty()
    }
}
