package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test

class NoSharedPreferencesForTokensTest {
    private val rule = NoSharedPreferencesForTokens(Config.empty)

    @Test
    fun `reports a getSharedPreferences call`() {
        val findings = rule.lint("fun prefs(context: Context) = context.getSharedPreferences(\"prefs\", 0)")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("DataStore")
    }

    @Test
    fun `reports an EncryptedSharedPreferences reference`() {
        val code =
            """
            fun prefs(context: Context) =
                EncryptedSharedPreferences.create(context, "secrets", masterKey, scheme, scheme)
            """.trimIndent()
        val findings = rule.lint(code)

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("DataStore")
    }

    @Test
    fun `reports an EncryptedSharedPreferences import`() {
        assertThat(rule.lint("import androidx.security.crypto.EncryptedSharedPreferences")).hasSize(1)
    }

    @Test
    fun `does not report DataStore usage`() {
        val code =
            """
            private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(
                name = "device_session",
            )
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report similarly named calls`() {
        assertThat(rule.lint("fun prefs() = getPreferences()")).isEmpty()
    }
}
