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

class NoCrossFeatureRouteImportTest {
    private val rule = NoCrossFeatureRouteImport(Config.empty)

    @Test
    fun `reports a route imported from another feature`() {
        val code =
            """
            package com.example.app.feature.files.presentation.browse

            import com.example.app.feature.auth.presentation.navigation.AuthRoute
            """.trimIndent()

        val findings = rule.lint(code)

        assertThat(findings).hasSize(1)
        assertThat(findings.first().message).contains("auth")
        assertThat(findings.first().message).contains("files")
    }

    @Test
    fun `reports a nested route member imported from another feature`() {
        val code =
            """
            package com.example.app.feature.files.presentation.browse

            import com.example.app.feature.auth.presentation.navigation.AuthRoute.SignIn
            """.trimIndent()

        assertThat(rule.lint(code)).hasSize(1)
    }

    @Test
    fun `does not report a route imported within the same feature`() {
        val code =
            """
            package com.example.app.feature.auth.presentation.signin

            import com.example.app.feature.auth.presentation.navigation.AuthRoute
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report imports without a feature segment`() {
        val code =
            """
            package com.example.app.feature.files.presentation.browse

            import com.example.app.core.presentation.navigation.SharedRoute
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report files outside a feature package`() {
        val code =
            """
            package com.example.app.main

            import com.example.app.feature.auth.presentation.navigation.AuthRoute
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `does not report cross-feature imports that are not routes`() {
        val code =
            """
            package com.example.app.feature.files.presentation.browse

            import com.example.app.feature.auth.presentation.model.SessionUi
            """.trimIndent()

        assertThat(rule.lint(code)).isEmpty()
    }

    @Test
    fun `fires in feature sources and is excluded in the app module`(
        @TempDir root: Path,
    ) {
        val violation =
            """
            package com.example.app.feature.files.presentation.browse

            import com.example.app.feature.auth.presentation.navigation.AuthRoute
            """.trimIndent()
        val scoped =
            NoCrossFeatureRouteImport(
                TestConfig(
                    "active" to true,
                    "excludes" to listOf("**/test/**", "**/androidTest/**", "**/app/src/**"),
                ),
            )
        val featureSource = "feature/files/presentation/src/main/java/com/example/app/feature/files/presentation/browse/FilesScreen.kt"
        val appSource = "app/src/main/java/com/example/app/main/NavigationRoot.kt"

        assertThat(scoped.lintAt(root, featureSource, violation)).hasSize(1)
        assertThat(scoped.lintAt(root, appSource, violation)).isEmpty()
        assertThat(scoped.lintAt(root, UNIT_TEST_PATH, violation)).isEmpty()
        assertThat(scoped.lintAt(root, INSTRUMENTATION_TEST_PATH, violation)).isEmpty()
    }
}
