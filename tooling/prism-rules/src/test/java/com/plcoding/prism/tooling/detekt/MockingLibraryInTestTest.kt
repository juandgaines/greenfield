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

class MockingLibraryInTestTest {
    private val rule = MockingLibraryInTest(Config.empty)

    @Test
    fun `reports a mockk import`() {
        val findings = rule.lint("import io.mockk.mockk\n\nclass Sample")

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("fake")
    }

    @Test
    fun `reports a mockito import`() {
        assertThat(rule.lint("import org.mockito.Mockito\n\nclass Sample")).hasSize(1)
    }

    @Test
    fun `reports an easymock import`() {
        assertThat(rule.lint("import org.easymock.EasyMock\n\nclass Sample")).hasSize(1)
    }

    @Test
    fun `does not report an unrelated import sharing a prefix`() {
        assertThat(rule.lint("import org.mockitoextras.Helper\n\nclass Sample")).isEmpty()
    }

    @Test
    fun `does not report a fake import`() {
        assertThat(rule.lint("import com.example.app.core.data.FakeClock\n\nclass Sample")).isEmpty()
    }

    @Test
    fun `honors a custom library list`() {
        val custom = MockingLibraryInTest(TestConfig("mockingLibraryPrefixes" to listOf("com.example.mocks")))

        assertThat(custom.lint("import com.example.mocks.Mock\n\nclass Sample")).hasSize(1)
        assertThat(custom.lint("import io.mockk.mockk\n\nclass Sample")).isEmpty()
    }

    @Test
    fun `fires under both test trees and stays out of main sources`(
        @TempDir root: Path,
    ) {
        val violation = "import io.mockk.mockk\n\nclass Sample"
        val scoped =
            MockingLibraryInTest(
                TestConfig("active" to true, "includes" to listOf("**/test/**", "**/androidTest/**")),
            )

        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, violation)).isEmpty()
    }
}
