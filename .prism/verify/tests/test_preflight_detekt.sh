# Tests for prism-doctor --preflight's detekt check. Sourced by run-tests.sh.
#
# detekt is the ONE version PRISM overrides rather than keeping, because the 72
# rules compile against one exact detekt-api and 2.0 is the alpha series where
# that API moves. So the check has four outcomes, not two, and three of them
# were wrong before these tests existed:
#
#   absent          -> pass, and say which version will be added
#   equals the pin  -> pass
#   2.x, not the pin-> FAIL naming both versions
#   1.x             -> FAIL, migration not install
#
# The third was reported as "no conflicting detekt install" -- the catalog
# lookup matched a [plugins] id or an alias literally spelled dev-detekt, and
# missed the most ordinary declaration of all: a [libraries] entry with
# group = "dev.detekt" and a version.ref, which is how PRISM itself declares it.
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

PD_DOCTOR="$HOOK_DIR/prism-doctor"
PD_PIN=$(sed -n 's/^detekt[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' \
    "$HOOK_DIR/../templates/libs.versions.toml.fragment" | head -1)

assert_ne "" "$PD_PIN" "the shipped fragment declares a detekt pin"

# A repository just complete enough for preflight: a settings file, a wrapper,
# and a catalog. No Gradle is invoked.
pd_repo() {
    repo=$(prism_fixture_repo)
    mkdir -p "$repo/gradle"
    : > "$repo/settings.gradle.kts"
    : > "$repo/gradlew"
    printf '[versions]\nkotlin = "2.4.10"\n%s\n' "$1" > "$repo/gradle/libs.versions.toml"
    printf '%s' "$repo"
}

pd_run() {
    ( cd "$1" && PRISM_ROOT="$1" sh "$PD_DOCTOR" --preflight 2>&1 )
}

# --- absent: say what will be added, do not just say "no conflict" --------
pd1=$(pd_repo "")
pd1_out=$(pd_run "$pd1")
assert_contains "$pd1_out" "no existing detekt" \
    "a repository with no detekt passes"
assert_contains "$pd1_out" "$PD_PIN" \
    "and the message names the version PRISM will add"

# --- exactly the pin ------------------------------------------------------
pd2=$(pd_repo "detekt = \"$PD_PIN\"
[libraries]
detekt-gradle-plugin = { group = \"dev.detekt\", name = \"detekt-gradle-plugin\", version.ref = \"detekt\" }")
assert_contains "$(pd_run "$pd2")" "matches PRISM's pin" \
    "a repository already on the pinned version passes"

# --- 2.x but NOT the pin: the case that used to report no conflict --------
#
# Declared the ordinary way -- a library entry with a group, no [plugins] alias
# anywhere -- which is exactly the shape the old lookup could not see.
pd3=$(pd_repo "detekt = \"2.0.0-alpha.4\"
[libraries]
detekt-gradle-plugin = { group = \"dev.detekt\", name = \"detekt-gradle-plugin\", version.ref = \"detekt\" }")
pd3_out=$(pd_run "$pd3")
assert_contains "$pd3_out" "pins detekt 2.0.0-alpha.4" \
    "a different 2.0 alpha is caught, not waved through"
assert_contains "$pd3_out" "$PD_PIN" \
    "and the failure names PRISM's pin alongside theirs"

# --- a literal version, no version.ref ------------------------------------
pd4=$(pd_repo "[libraries]
detekt-api = { module = \"dev.detekt:detekt-api\", version = \"2.0.0-alpha.3\" }")
assert_contains "$(pd_run "$pd4")" "2.0.0-alpha.3" \
    "an inline version on a module coordinate is found too"

# --- 1.x is a migration, and outranks everything else ---------------------
pd5=$(pd_repo "detekt = \"1.23.6\"
[plugins]
detekt = { id = \"io.gitlab.arturbosch.detekt\", version.ref = \"detekt\" }")
pd5_out=$(pd_run "$pd5")
assert_contains "$pd5_out" "detekt 1.x" \
    "the old coordinates are reported as a migration"
assert_contains "$pd5_out" "MIGRATION" \
    "and the message says it is not an install"

rm -rf "$pd1" "$pd2" "$pd3" "$pd4" "$pd5"
