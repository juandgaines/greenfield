package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The remaining regression guards: Compose, resources, flows, and HTTP. */
class RegressionGuardComposeTest {
    @Nested
    inner class HardcodedUserFacingStringTest {
        private val rule = HardcodedUserFacingString(Config.empty)

        @Test
        fun `reports a literal passed to Text`() {
            assertThat(rule.lint("""@Composable fun Row() { Text("Sign in") }""")).hasSize(1)
        }

        @Test
        fun `reports a literal text argument`() {
            assertThat(rule.lint("""@Composable fun Row() { Label(text = "Sign in") }""")).hasSize(1)
        }

        @Test
        fun `reports a literal content description`() {
            assertThat(rule.lint("""@Composable fun Row() { Icon(painter = p, contentDescription = "Close") }""")).hasSize(1)
        }

        @Test
        fun `does not report a string resource`() {
            assertThat(rule.lint("@Composable fun Row() { Text(stringResource(R.string.sign_in)) }")).isEmpty()
        }

        @Test
        fun `does not report an interpolated value`() {
            assertThat(rule.lint("""@Composable fun Row(name: String) { Text("${'$'}name") }""")).isEmpty()
        }

        @Test
        fun `does not report a null content description`() {
            assertThat(rule.lint("@Composable fun Row() { Icon(painter = p, contentDescription = null) }")).isEmpty()
        }

        @Test
        fun `is exempt inside a preview`() {
            assertThat(rule.lint("""@Preview @Composable private fun P() { Text("placeholder") }""")).isEmpty()
        }

        @Test
        fun `is exempt inside a multi-preview annotation`() {
            assertThat(rule.lint("""@ThemePreviews @Composable private fun P() { Text("placeholder") }""")).isEmpty()
        }

        @Test
        fun `still fires in a shipped composable and is excluded in test sources`(
            @TempDir root: Path,
        ) {
            val shipped = """@Composable fun Row() { Text("Sign in") }"""
            val scoped = HardcodedUserFacingString(TestConfig(*MAIN_SOURCES_ONLY))
            assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, shipped)).hasSize(1)
            assertThat(scoped.lintAt(root, UNIT_TEST_PATH, shipped)).isEmpty()
        }
    }

    @Nested
    inner class RawColorLiteralTest {
        private val rule = RawColorLiteral(Config.empty)

        @Test
        fun `reports a hex colour literal`() {
            assertThat(rule.lint("val accent = Color(0xFF4F8EF7)")).hasSize(1)
        }

        @Test
        fun `does not report a themed colour`() {
            assertThat(rule.lint("@Composable fun tint() = MaterialTheme.colorScheme.primary")).isEmpty()
        }

        @Test
        fun `does not report a colour built from named values`() {
            assertThat(rule.lint("val accent = Color(red = channel, green = channel, blue = channel)")).isEmpty()
        }

        @Test
        fun `fires outside the theme package and is excluded inside it`(
            @TempDir root: Path,
        ) {
            val code = "val accent = Color(0xFF4F8EF7)"
            val scoped =
                RawColorLiteral(
                    TestConfig(
                        "active" to true,
                        "excludes" to listOf("**/test/**", "**/androidTest/**", "**/designsystem/theme/**"),
                    ),
                )
            val inTheme = "core/design-system/src/main/java/com/example/app/core/designsystem/theme/Color.kt"
            val inScreen = "feature/auth/presentation/src/main/java/com/example/app/SignInScreen.kt"
            assertThat(scoped.lintAt(root, inScreen, code)).hasSize(1)
            assertThat(scoped.lintAt(root, inTheme, code)).isEmpty()
        }
    }

    @Nested
    inner class NoBareIconClickableTest {
        private val rule = NoBareIconClickable(Config.empty)

        @Test
        fun `reports a clickable Icon`() {
            assertThat(
                rule.lint("@Composable fun Row() { Icon(painter = p, contentDescription = null, modifier = Modifier.clickable { go() }) }"),
            ).hasSize(1)
        }

        @Test
        fun `does not report an Icon inside an IconButton`() {
            assertThat(
                rule.lint("@Composable fun Row() { IconButton(onClick = ::go) { Icon(painter = p, contentDescription = null) } }"),
            ).isEmpty()
        }

        @Test
        fun `does not report an Icon with a non-clickable modifier`() {
            assertThat(
                rule.lint("@Composable fun Row() { Icon(painter = p, contentDescription = null, modifier = Modifier.size(16.dp)) }"),
            ).isEmpty()
        }

        @Test
        fun `fires in a main source and is excluded in test sources`(
            @TempDir root: Path,
        ) {
            val code = "@Composable fun Row() { Icon(painter = p, contentDescription = null, modifier = Modifier.clickable { go() }) }"
            val scoped = NoBareIconClickable(TestConfig(*MAIN_SOURCES_ONLY))
            assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, code)).hasSize(1)
            assertThat(scoped.lintAt(root, UNIT_TEST_PATH, code)).isEmpty()
        }
    }

    @Nested
    inner class NoComposableModifierExtensionTest {
        private val rule = NoComposableModifierExtension(Config.empty)

        @Test
        fun `reports a composable modifier extension`() {
            assertThat(rule.lint("@Composable fun Modifier.shimmer(): Modifier = this")).hasSize(1)
        }

        @Test
        fun `does not report a plain modifier extension`() {
            assertThat(rule.lint("fun Modifier.shimmer(): Modifier = this")).isEmpty()
        }

        @Test
        fun `does not report a composable that is not a modifier extension`() {
            assertThat(rule.lint("@Composable fun Shimmer() = Unit")).isEmpty()
        }

        @Test
        fun `fires in a main source and is excluded in test sources`(
            @TempDir root: Path,
        ) {
            val code = "@Composable fun Modifier.shimmer(): Modifier = this"
            val scoped = NoComposableModifierExtension(TestConfig(*MAIN_SOURCES_ONLY))
            assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, code)).hasSize(1)
            assertThat(scoped.lintAt(root, UNIT_TEST_PATH, code)).isEmpty()
        }
    }

    @Nested
    inner class CallbackFlowRequiresAwaitCloseTest {
        private val rule = CallbackFlowRequiresAwaitClose(Config.empty)

        @Test
        fun `reports a callbackFlow with no awaitClose`() {
            assertThat(rule.lint("fun events() = callbackFlow { register { trySend(it) } }")).hasSize(1)
        }

        @Test
        fun `does not report a callbackFlow that awaits close`() {
            assertThat(
                rule.lint("fun events() = callbackFlow { register { trySend(it) }; awaitClose { unregister() } }"),
            ).isEmpty()
        }

        @Test
        fun `does not report an ordinary flow builder`() {
            assertThat(rule.lint("fun events() = flow { emit(1) }")).isEmpty()
        }

        @Test
        fun `fires in a main source and is excluded in test sources`(
            @TempDir root: Path,
        ) {
            val code = "fun events() = callbackFlow { register { trySend(it) } }"
            val scoped = CallbackFlowRequiresAwaitClose(TestConfig(*MAIN_SOURCES_ONLY))
            assertThat(scoped.lintAt(root, MAIN_SOURCE_PATH, code)).hasSize(1)
            assertThat(scoped.lintAt(root, UNIT_TEST_PATH, code)).isEmpty()
        }
    }

    @Nested
    inner class KtorCallMustUseSafeCallTest {
        private val rule = KtorCallMustUseSafeCall(Config.empty)

        @Test
        fun `reports a direct verb on the client`() {
            assertThat(rule.lint("""suspend fun load() = httpClient.get("/items")""")).hasSize(1)
        }

        @Test
        fun `does not report a verb wrapped in safeCall`() {
            assertThat(
                rule.lint("""suspend fun load() = safeCall { httpClient.post { url("/items") } }"""),
            ).isEmpty()
        }

        @Test
        fun `does not report the safe wrappers themselves`() {
            assertThat(rule.lint("""suspend fun load() = httpClient.safeGet<Item>(route = "/items")""")).isEmpty()
        }

        @Test
        fun `does not report an unrelated get on another receiver`() {
            assertThat(rule.lint("fun read(cache: Map<String, Item>) = cache.get(\"key\")")).isEmpty()
        }

        @Test
        fun `fires in a feature data module and is excluded in the wrapper's own package`(
            @TempDir root: Path,
        ) {
            val code = """suspend fun load() = httpClient.get("/items")"""
            val scoped =
                KtorCallMustUseSafeCall(
                    TestConfig(
                        "active" to true,
                        "excludes" to listOf("**/test/**", "**/androidTest/**", "**/core/data/networking/**"),
                    ),
                )
            val featurePath = "feature/auth/data/src/main/java/com/example/app/feature/auth/data/networking/Api.kt"
            val wrapperPath = "core/data/src/main/java/com/example/app/core/data/networking/SafeCall.kt"
            assertThat(scoped.lintAt(root, featurePath, code)).hasSize(1)
            assertThat(scoped.lintAt(root, wrapperPath, code)).isEmpty()
        }
    }
}
