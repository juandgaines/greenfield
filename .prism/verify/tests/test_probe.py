#!/usr/bin/env python3
"""Tests for the design-system probe.

The probe replaced an agent's judgment, so what it owes is not cleverness but
SAMENESS: the same repository shape must produce the same five values every
time, and a shape it cannot read must say so rather than choose. These tests
are therefore weighted towards the two answers that used to go wrong --
"there is no palette declaration" and "there is more than one" -- because a
confident wrong name is the silent green this whole mechanism exists to stop.
"""

import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "lib"))

import probe  # noqa: E402


class ProbeTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="prism-probe-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def kt(self, relpath, text):
        target = os.path.join(self.tmp, relpath)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "w", encoding="utf-8") as handle:
            handle.write(text)
        return target

    def run_probe(self):
        return probe.resolve(probe.probe(self.tmp))

    # --- the shape that broke: colours as top-level vals ------------------
    #
    # The measured repository. It HAS a theme composable, a token holder and a
    # theme package -- everything an eyeball check reads as "yes, a design
    # system" -- and no declaration holding the colours. Four of ten installs
    # answered this wrongly, and both rules then passed over five real
    # violations.
    def test_top_level_colours_yield_no_palette(self):
        self.kt("app/src/main/kotlin/theme/Color.kt", """
package theme
import androidx.compose.ui.graphics.Color
val Purple80 = Color(0xFFD0BCFF)
val Purple40 = Color(0xFF6650a4)
val Pink80 = Color(0xFFEFB8C8)
""")
        self.kt("app/src/main/kotlin/theme/Theme.kt", """
package theme
@Composable
fun AppTheme(content: @Composable () -> Unit) {}
""")
        values, evidence, ambiguous = self.run_probe()
        self.assertEqual("none", values["PALETTE_OBJECT"])
        self.assertEqual("false", values["PALETTE_RULES_ACTIVE"])
        self.assertEqual("AppTheme", values["THEME_OBJECT"])
        self.assertEqual("true", values["THEME_RULES_ACTIVE"])
        self.assertEqual([], ambiguous)

    # --- the shape it is meant to find ------------------------------------
    def test_object_palette_is_named(self):
        self.kt("ds/src/main/kotlin/theme/Color.kt", """
package theme
object AppColors {
    val purple = Color(0xFFD0BCFF)
    val pink = Color(0xFFEFB8C8)
    val teal = Color(0xFF03DAC5)
}
data class ExtendedColors(
    val success: Color,
    val warning: Color,
)
val LocalExtendedColors = staticCompositionLocalOf<ExtendedColors> { error("none") }
""")
        values, _, ambiguous = self.run_probe()
        self.assertEqual("AppColors", values["PALETTE_OBJECT"])
        self.assertEqual("ExtendedColors", values["EXTENDED_TOKEN_HOLDER"])
        self.assertEqual([], ambiguous)

    # --- it must NOT guess -------------------------------------------------
    def test_two_palettes_refuse_to_choose(self):
        for name in ("BrandColors", "LegacyColors"):
            self.kt("ds/src/main/kotlin/theme/%s.kt" % name, """
package theme
object %s {
    val one = Color(0xFF000001)
    val two = Color(0xFF000002)
    val three = Color(0xFF000003)
}
""" % name)
        values, _, ambiguous = self.run_probe()
        self.assertEqual("none", values["PALETTE_OBJECT"])
        self.assertEqual("false", values["PALETTE_RULES_ACTIVE"])
        self.assertTrue(any("PALETTE_OBJECT" in line for line in ambiguous))
        self.assertIn("BrandColors", " ".join(ambiguous))
        self.assertIn("LegacyColors", " ".join(ambiguous))

    # --- a name found but shelved is reported as such ---------------------
    #
    # "We looked and found nothing" and "we found one but its group is off"
    # are different facts. Collapsing them loses the only trace that the
    # decision is worth reopening.
    def test_shelved_name_says_it_was_found(self):
        self.kt("app/src/main/kotlin/theme/Color.kt", """
package theme
val Purple80 = Color(0xFFD0BCFF)
val Purple40 = Color(0xFF6650a4)
data class ExtendedColors(
    val success: Color,
    val warning: Color,
)
""")
        values, evidence, _ = self.run_probe()
        self.assertEqual("none", values["EXTENDED_TOKEN_HOLDER"])
        self.assertIn("found at", evidence["EXTENDED_TOKEN_HOLDER"])
        self.assertIn("PALETTE_RULES_ACTIVE=false", evidence["EXTENDED_TOKEN_HOLDER"])

    # --- scope ------------------------------------------------------------
    def test_prism_own_modules_and_tests_are_not_the_consumers(self):
        self.kt("tooling/prism-rules/src/main/kotlin/Fixture.kt", """
object PrismFixtureColors {
    val a = Color(0xFF000001)
    val b = Color(0xFF000002)
    val c = Color(0xFF000003)
}
""")
        self.kt("app/src/test/kotlin/ColorTest.kt", """
object TestColors {
    val a = Color(0xFF000001)
    val b = Color(0xFF000002)
    val c = Color(0xFF000003)
}
""")
        self.kt("app/build/generated/Gen.kt", """
object GeneratedColors {
    val a = Color(0xFF000001)
    val b = Color(0xFF000002)
    val c = Color(0xFF000003)
}
""")
        values, _, ambiguous = self.run_probe()
        self.assertEqual("none", values["PALETTE_OBJECT"])
        self.assertEqual([], ambiguous)

    # --- determinism, stated as a test ------------------------------------
    def test_same_tree_gives_the_same_answer(self):
        self.kt("ds/src/main/kotlin/theme/Color.kt", """
object AppColors {
    val a = Color(0xFF000001)
    val b = Color(0xFF000002)
    val c = Color(0xFF000003)
}
""")
        first = self.run_probe()[0]
        for _ in range(4):
            self.assertEqual(first, self.run_probe()[0])


if __name__ == "__main__":
    unittest.main()
