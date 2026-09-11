# Tests for lib/affected.sh. Sourced by run-tests.sh.
# shellcheck disable=SC1091
. "$HOOK_DIR/lib/affected.sh"
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

# Every assertion below about module mapping, JVM-only detection, source
# hashing and dependency reach runs against a FIXTURE module graph, not
# against whichever checkout is open.
#
# They used to run against the ambient repository, which worked only while
# this repository was itself the Android project under test. Extracting the
# framework into a product repository ended that: prism_module_for_path
# returned empty for every path and ten assertions failed for a reason that
# had nothing to do with module mapping.
#
# That is the same bug, and the same fix, as the branch-state cases at 7.5.2
# below — a module graph is a property of a repository, so it belongs to a
# fixture repository. See prism_fixture_graph_repo.
graph_repo=$(prism_fixture_graph_repo)
PRISM_ROOT="$graph_repo"

# --- PRISM'S OWN MODULES ARE NOT THE CONSUMER'S CODE ---------------------
#
# 0.6.0. The install declares two Gradle modules in the consumer's
# settings.gradle.kts -- the detekt rule set and the konsist suite -- and every
# per-module gate iterated over them like any other. `:tooling:konsist` has a
# src/test and NO src/main, so the coverage gate measured a report with zero
# lines, called it `no-line-data`, and DENIED THE PUSH on a module the installer
# had just placed. A framework must not fail a repository on its own code.
#
# They are still checked by what suits them: gate 0's staticAnalysis aggregate
# runs `:tooling:konsist:test`, and the rule set has its own suite.
own_repo=$(prism_fixture_graph_repo)
printf 'include(":tooling:prism-rules")\ninclude(":tooling:konsist")\n' \
    >> "$own_repo/settings.gradle.kts"
mkdir -p "$own_repo/tooling/konsist/src/test/kotlin" "$own_repo/tooling/prism-rules/src/main/kotlin"
printf 'plugins {}\n' > "$own_repo/tooling/konsist/build.gradle.kts"
printf 'plugins {}\n' > "$own_repo/tooling/prism-rules/build.gradle.kts"

own_all=$(PRISM_ROOT="$own_repo" prism_all_modules)
assert_not_contains "$own_all" ":tooling:konsist" \
    "the konsist suite is not one of the modules the gates iterate"
assert_not_contains "$own_all" ":tooling:prism-rules" \
    "and neither is the rule set"
assert_contains "$own_all" ":core:domain" \
    "while the consumer's own modules are all still there"
assert_eq "" \
    "$(PRISM_ROOT="$own_repo" prism_module_for_path tooling/konsist/src/test/kotlin/A.kt)" \
    "a change to PRISM's own source belongs to no gated module"

# ONE DEFINITION. lib/scope.py holds the same exclusion for the posture table,
# and this file's own history says a definition kept in two places drifted apart
# twice in one branch -- detekt.yml missing from one copy, gradle/wrapper from
# another. So the two lists are compared rather than trusted.
assert_eq "$(prism_own_modules | sort)" \
    "$(python3 -c "
import sys
sys.path.insert(0, '$HOOK_DIR/lib')
import re
source = open('$HOOK_DIR/lib/scope.py').read()
found = re.search(r'if m not in \(([^)]*)\)', source).group(1)
print('\n'.join(sorted(re.findall(r'\"(:[^\"]+)\"', found))))
")" \
    "affected.sh and scope.py exclude exactly the same modules"

rm -rf "$own_repo"

# --- prism_module_for_path: longest-prefix wins ---
assert_eq ":feature:files:presentation" \
    "$(prism_module_for_path feature/files/presentation/src/main/java/A.kt)" \
    "nested feature module maps to its own gradle path"

assert_eq ":core:crypto" \
    "$(prism_module_for_path core/crypto/src/test/java/B.kt)" \
    "core module maps correctly"

assert_eq ":app" \
    "$(prism_module_for_path app/src/main/java/MainActivity.kt)" \
    "app module maps correctly"

assert_eq "" \
    "$(prism_module_for_path docs/superpowers/plans/x.md)" \
    "a file outside every module maps to nothing"

assert_eq "" \
    "$(prism_module_for_path settings.gradle.kts)" \
    "a root build file maps to no module"

# --- prism_modules_for_files: dedupes and drops non-module paths ---
actual=$(printf '%s\n' \
    "feature/files/presentation/src/main/java/A.kt" \
    "feature/files/presentation/src/main/java/B.kt" \
    "core/crypto/src/main/java/C.kt" \
    "README.md" | prism_modules_for_files | tr '\n' ' ')
assert_eq ":core:crypto :feature:files:presentation " "$actual" \
    "modules are deduped, sorted, and non-module paths dropped"

# --- prism_module_dir ---
assert_eq "feature/files/presentation" \
    "$(prism_module_dir :feature:files:presentation)" \
    "gradle path converts to directory"

# --- prism_is_jvm_only ---
if prism_is_jvm_only :core:domain; then
    assert_eq "yes" "yes" "core:domain is detected as JVM-only"
else
    assert_eq "yes" "no" "core:domain is detected as JVM-only"
fi

if prism_is_jvm_only :feature:files:presentation; then
    assert_eq "no" "yes" "presentation module is NOT JVM-only"
else
    assert_eq "no" "no" "presentation module is NOT JVM-only"
fi

# --- prism_source_hash is stable and content-sensitive ---
hash_a=$(prism_source_hash :core:domain)
hash_b=$(prism_source_hash :core:domain)
assert_eq "$hash_a" "$hash_b" "source hash is stable across calls"
assert_eq "64" "$(printf '%s' "$hash_a" | wc -c | tr -d ' ')" \
    "source hash is a 64-char sha256 hex string"

hash_two=$(prism_source_hash :core:domain :core:crypto)
assert_not_contains "$hash_two" "$hash_a" "adding a module changes the hash"

# --- prism_merge_base: resolves via a real ref, fails loudly otherwise ---
base=$(PRISM_BASE_BRANCH=HEAD prism_merge_base)
assert_eq "0" "$?" "prism_merge_base exits 0 when the ref resolves"
if [ -n "$base" ]; then
    assert_eq "yes" "yes" "prism_merge_base prints a base when the ref resolves"
else
    assert_eq "yes" "no" "prism_merge_base prints a base when the ref resolves"
fi

out=$(PRISM_BASE_BRANCH=totally-nonexistent-ref-xyz prism_merge_base)
assert_eq "1" "$?" "prism_merge_base fails when neither ref exists"
assert_eq "" "$out" "prism_merge_base prints nothing on failure"

# --- prism_push_range: asserted against fixtures, not this checkout -------
# 7.5.2. These used to run against whatever branch the developer had open,
# under a comment reading "this repo branch has no upstream (verified)". The
# moment the branch was pushed that stopped being true, prism_push_range
# correctly preferred the upstream, and two assertions about UNRESOLVABLE
# BASES began failing for a reason that had nothing to do with bases. Whether
# a branch has an upstream is a property of a repository, so it belongs to a
# fixture repository.
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

range_repo=$(prism_fixture_repo)

# No upstream, resolvable base: the merge-base fallback.
range=$(PRISM_ROOT="$range_repo" PRISM_BASE_BRANCH=master prism_push_range)
assert_eq "0" "$?" "prism_push_range exits 0 when the base resolves"
case "$range" in
    ?*..HEAD) assert_eq "yes" "yes" "the range is non-empty and ends at ..HEAD" ;;
    *) assert_eq "yes" "no" "the range is non-empty and ends at ..HEAD (got [$range])" ;;
esac

# No upstream, unresolvable base: failure, never "nothing changed".
range=$(PRISM_ROOT="$range_repo" \
    PRISM_BASE_BRANCH=totally-nonexistent-ref-xyz prism_push_range)
assert_eq "1" "$?" "prism_push_range fails when the base cannot be resolved"
assert_eq "" "$range" "prism_push_range prints nothing when the base cannot be resolved"

# An upstream exists: it wins, and the base branch stops mattering at all.
# This is the case the old assertions silently drifted into.
range_clone=$(mktemp -d)
range_clone=$(CDPATH= cd -- "$range_clone" && pwd -P)/clone
git clone -q "$range_repo" "$range_clone"
range=$(PRISM_ROOT="$range_clone" \
    PRISM_BASE_BRANCH=totally-nonexistent-ref-xyz prism_push_range)
assert_eq "0" "$?" "an upstream makes an unresolvable base branch irrelevant"
assert_eq "origin/master..HEAD" "$range" "the range starts at the upstream"
rm -rf "$range_clone"
rm -rf "$range_repo"

# --- prism_changed_files_stop: also signals failure rather than an empty diff ---
out=$(PRISM_BASE_BRANCH=totally-nonexistent-ref-xyz prism_changed_files_stop)
assert_eq "1" "$?" "prism_changed_files_stop fails when the base cannot be resolved"

# --- prism_changed_files_range: signals failure on an unresolvable range,
# rather than reading as an empty (nothing changed) diff. Previously this
# piped straight into `sort -u`, which exits 0 on empty input regardless of
# whether `git diff` itself failed, hiding e.g. a stale @{upstream}. ---
out=$(prism_changed_files_range "no-such-ref..HEAD")
assert_eq "1" "$?" "prism_changed_files_range fails on an unresolvable range"
assert_eq "" "$out" "prism_changed_files_range prints nothing when the range fails"

# A genuinely empty (but VALID) range must still succeed with nothing changed.
out=$(prism_changed_files_range "HEAD..HEAD")
assert_eq "0" "$?" "prism_changed_files_range succeeds on a valid empty range"

# A real, valid range with actual changes still succeeds and lists files.
# (master exists locally in this repo — verified earlier in this feature's
# work — so this exercises a genuinely non-empty diff, not another trivial
# same-commit range.)
range=$(PRISM_BASE_BRANCH=master prism_merge_base)
out=$(prism_changed_files_range "$range..HEAD")
assert_eq "0" "$?" "prism_changed_files_range succeeds on a real range"

# --- prism_source_hash covers test sources too ---------------------------
# The Stop gate RUNS src/test and src/androidTest, so a change there changes
# what was actually verified and must invalidate its pass-cache.
hash_root=$(mktemp -d)
mkdir -p "$hash_root/mod/src/main/java" "$hash_root/mod/src/test/java"
printf 'fun main() {}\n' > "$hash_root/mod/src/main/java/Main.kt"
printf 'fun test() {}\n' > "$hash_root/mod/src/test/java/MainTest.kt"
printf 'plugins { }\n' > "$hash_root/mod/build.gradle.kts"

full_before=$(PRISM_ROOT="$hash_root" prism_source_hash :mod)
printf 'fun test2() {}\n' >> "$hash_root/mod/src/test/java/MainTest.kt"
full_after_test_edit=$(PRISM_ROOT="$hash_root" prism_source_hash :mod)
if [ "$full_after_test_edit" != "$full_before" ]; then
    assert_eq "yes" "yes" \
        "prism_source_hash CHANGES when a src/test file changes (Stop gate runs it)"
else
    assert_eq "yes" "no" \
        "prism_source_hash CHANGES when a src/test file changes (Stop gate runs it)"
fi

printf 'fun main2() {}\n' >> "$hash_root/mod/src/main/java/Main.kt"
full_after_main_edit=$(PRISM_ROOT="$hash_root" prism_source_hash :mod)
if [ "$full_after_main_edit" != "$full_after_test_edit" ]; then
    assert_eq "yes" "yes" "prism_source_hash CHANGES when a src/main file changes"
else
    assert_eq "yes" "no" "prism_source_hash CHANGES when a src/main file changes"
fi

rm -rf "$hash_root"

# --- prism_with_dependents: Gradle builds dependencies, never dependents --
# Verifying only the edited module is not Principle VII gate 2: a signature
# change in :core:domain leaves every consumer broken while :core:domain's
# own tasks pass green.
closure=$(prism_with_dependents :core:domain | tr '\n' ' ')
assert_contains "$closure" ":core:domain" "the seed module is in its own closure"
assert_contains "$closure" ":feature:auth:presentation" \
    "a :core:domain change reaches the auth presentation module"
assert_contains "$closure" ":app" "a :core:domain change reaches :app"

# A leaf pulls in nothing but itself.
closure=$(prism_with_dependents :app | tr '\n' ' ')
assert_eq ":app " "$closure" ":app has no dependents of its own"

# androidTest-only edges count: the fixture's :feature:files:presentation
# consumes :core:data via androidTestImplementation, and a module whose tests
# no longer compile is not a module that passed gate 2. Named against the
# fixture graph rather than the origin repository's modules this once asserted on
# (:feature:files:data over :core:files-db), which no longer exist here.
closure=$(prism_with_dependents :core:data | tr '\n' ' ')
assert_contains "$closure" ":feature:files:presentation" \
    "an androidTestImplementation edge is a real dependency"

# An unknown module passes through rather than raising, so the test suite's
# :no:such:module still reaches the coverage pre-check that denies it.
closure=$(prism_with_dependents :no:such:module | tr '\n' ' ')
assert_eq ":no:such:module " "$closure" "an unknown module passes through unchanged"

# A broken graph is a HARD ERROR: silently verifying the narrower scope is
# the exact failure this closes.
stub_dir=$(mktemp -d)
printf '#!/bin/sh\nexit 127\n' > "$stub_dir/python3"
chmod +x "$stub_dir/python3"
out=$(PATH="$stub_dir:$PATH" prism_with_dependents :core:domain)
assert_eq "1" "$?" "prism_with_dependents fails when the graph cannot be read"
assert_eq "" "$out" "prism_with_dependents prints nothing on failure"
rm -rf "$stub_dir"

# --- prism_source_hash covers the global build files ---------------------
# A convention plugin or version-catalog edit changes how every module
# builds but belongs to none of them, so it left this hash untouched and a
# stale Stop pass-cache entry stayed valid across it.
hash_root=$(mktemp -d)
mkdir -p "$hash_root/mod/src/main/java" "$hash_root/build-logic/src/main/kotlin"
printf 'fun main() {}\n' > "$hash_root/mod/src/main/java/Main.kt"
printf 'plugins { }\n' > "$hash_root/mod/build.gradle.kts"
printf 'agp = "9.0.0"\n' > "$hash_root/gradle-catalog-placeholder"
mkdir -p "$hash_root/gradle"
printf 'agp = "9.0.0"\n' > "$hash_root/gradle/libs.versions.toml"
printf 'class Conv\n' > "$hash_root/build-logic/src/main/kotlin/Conv.kt"

before=$(PRISM_ROOT="$hash_root" prism_source_hash :mod)
printf 'agp = "9.1.0"\n' > "$hash_root/gradle/libs.versions.toml"
after_catalog=$(PRISM_ROOT="$hash_root" prism_source_hash :mod)
if [ "$after_catalog" != "$before" ]; then
    assert_eq "yes" "yes" "a version-catalog edit invalidates the Stop pass-cache"
else
    assert_eq "yes" "no" "a version-catalog edit invalidates the Stop pass-cache"
fi

printf 'class Conv { val x = 1 }\n' > "$hash_root/build-logic/src/main/kotlin/Conv.kt"
after_plugin=$(PRISM_ROOT="$hash_root" prism_source_hash :mod)
if [ "$after_plugin" != "$after_catalog" ]; then
    assert_eq "yes" "yes" "a convention-plugin edit invalidates the Stop pass-cache"
else
    assert_eq "yes" "no" "a convention-plugin edit invalidates the Stop pass-cache"
fi

printf 'complexity:\n' > "$hash_root/detekt.yml"
after_detekt=$(PRISM_ROOT="$hash_root" prism_source_hash :mod)
if [ "$after_detekt" != "$after_plugin" ]; then
    assert_eq "yes" "yes" "a detekt.yml edit invalidates the Stop pass-cache"
else
    assert_eq "yes" "no" "a detekt.yml edit invalidates the Stop pass-cache"
fi

rm -rf "$hash_root"

# --- the rule sets and the wrapper are global build files ------------------
# detekt.yml is the other half of gate 0's rule set and the wrapper can break
# every module's build; each was missing from one hand-maintained copy of
# this classification before it was single-sourced.
if prism_is_global_build_file "detekt.yml"; then
    assert_eq "yes" "yes" "detekt.yml is a global build file"
else
    assert_eq "yes" "no" "detekt.yml is a global build file"
fi
if prism_is_global_build_file "gradle/wrapper/gradle-wrapper.properties"; then
    assert_eq "yes" "yes" "the gradle wrapper is a global build file"
else
    assert_eq "yes" "no" "the gradle wrapper is a global build file"
fi
if prism_is_global_build_file "core/domain/src/main/java/Seed.kt"; then
    assert_eq "no" "yes" "an ordinary module source is NOT a global build file"
else
    assert_eq "no" "no" "an ordinary module source is NOT a global build file"
fi

# ktlint resolves .editorconfig hierarchically, so a MODULE-LOCAL copy can
# relax rules for everything under its directory. Matching only the root
# copies made that the one edit that skipped the gate it relaxes.
if prism_is_global_build_file "core/data/.editorconfig"; then
    assert_eq "yes" "yes" "a module-local .editorconfig is a global build file"
else
    assert_eq "yes" "no" "a module-local .editorconfig is a global build file"
fi
if prism_is_global_build_file "feature/auth/data/detekt.yml"; then
    assert_eq "yes" "yes" "a module-local detekt.yml is a global build file"
else
    assert_eq "yes" "no" "a module-local detekt.yml is a global build file"
fi

# --- the fingerprint sees every global build path --------------------------
# The fingerprint's pathspecs are derived from the same list as the matcher
# above; when they were a separate copy, a wrapper bump and a detekt.yml edit
# widened the gate's scope to every module while staying invisible to the
# short-circuit that decides whether the gate runs at all.
fp_repo=$(prism_fixture_repo)
fp_before=$(PRISM_ROOT="$fp_repo" prism_turn_fingerprint)
printf 'complexity:\n  active: true\n' > "$fp_repo/detekt.yml"
fp_after_detekt=$(PRISM_ROOT="$fp_repo" prism_turn_fingerprint)
if [ "$fp_after_detekt" != "$fp_before" ]; then
    assert_eq "yes" "yes" "a detekt.yml edit changes the turn fingerprint"
else
    assert_eq "yes" "no" "a detekt.yml edit changes the turn fingerprint"
fi
mkdir -p "$fp_repo/gradle/wrapper"
printf 'distributionUrl=gradle-9.1\n' \
    > "$fp_repo/gradle/wrapper/gradle-wrapper.properties"
fp_after_wrapper=$(PRISM_ROOT="$fp_repo" prism_turn_fingerprint)
if [ "$fp_after_wrapper" != "$fp_after_detekt" ]; then
    assert_eq "yes" "yes" "a wrapper edit changes the turn fingerprint"
else
    assert_eq "yes" "no" "a wrapper edit changes the turn fingerprint"
fi

# A module-local rule-set copy must be visible to the fingerprint too, or a
# turn that only relaxes one module's rules skips every later stop as well.
printf '[*.kt]\nmax_line_length = 200\n' > "$fp_repo/core/domain/.editorconfig"
fp_after_local_rules=$(PRISM_ROOT="$fp_repo" prism_turn_fingerprint)
if [ "$fp_after_local_rules" != "$fp_after_wrapper" ]; then
    assert_eq "yes" "yes" "a module-local .editorconfig changes the turn fingerprint"
else
    assert_eq "yes" "no" "a module-local .editorconfig changes the turn fingerprint"
fi

# Resources reach assembleDebug (a deleted string breaks R.string consumers),
# so a res edit after a sign-off must re-run the gate, not ride the verdict.
mkdir -p "$fp_repo/core/domain/src/main/res/values"
printf '<resources></resources>\n' \
    > "$fp_repo/core/domain/src/main/res/values/strings.xml"
fp_after_res=$(PRISM_ROOT="$fp_repo" prism_turn_fingerprint)
if [ "$fp_after_res" != "$fp_after_local_rules" ]; then
    assert_eq "yes" "yes" "a resource edit changes the turn fingerprint"
else
    assert_eq "yes" "no" "a resource edit changes the turn fingerprint"
fi
rm -rf "$fp_repo"

# --- prism_with_dependents must widen from zsh too ------------------------
# zsh does not word-split an unquoted `$modules`, so a multi-module list
# reaches the function as ONE newline-joined argument. The positional
# --module encoding turned that blob into a single unknown module that
# deps.py's pass-through contract echoed back — the seeds returned with no
# dependents and no error, the exact silent under-widening the hard-error
# contract exists to prevent.
if command -v zsh >/dev/null 2>&1; then
    zsh_closure=$(PRISM_ROOT="$PRISM_ROOT" HOOK_DIR="$HOOK_DIR" zsh -c '
        . "$HOOK_DIR/lib/affected.sh"
        mods=$(printf ":core:domain\n:core:crypto")
        prism_with_dependents $mods
    ' | tr '\n' ' ')
    assert_contains "$zsh_closure" ":app" \
        "a zsh caller passing a multi-module blob still gets the dependents"
    assert_contains "$zsh_closure" ":core:crypto" \
        "and every seed survives the round trip"
else
    assert_eq "zsh" "zsh" "zsh unavailable; dependents regression not exercised here"
fi

rm -rf "$graph_repo"
