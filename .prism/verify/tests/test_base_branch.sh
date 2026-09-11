# Tests for the base branch — the one configuration knob that travels the whole
# route in this phase. Sourced by run-tests.sh.
#
# It is declared in templates/parameters.json, rendered into .prism/prism.json
# by lib/render.py, and read back by lib/config.sh through
# prism_require_base_branch. This file is the evidence that the route works end
# to end rather than in three separately-tested halves: one knob makes the trip
# now so that a defect in the mechanism surfaces with a single caller written
# against it rather than nine (design D6).
#
# What it replaced was `PRISM_BASE_BRANCH="${PRISM_BASE_BRANCH:-master}"`, and
# the failure that default produced is the one worth keeping in view. A consumer
# whose trunk is `main` got a gate that either resolved no merge base at all or,
# with a stale local `master` lying around, resolved one against the wrong ref
# and verified a scope that was not the change. Nothing said so, because a
# default that is present looks exactly like a value that was chosen.
# shellcheck disable=SC1091
. "$HOOK_DIR/lib/affected.sh"
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

# --- the round trip ------------------------------------------------------
#
# A fixture whose history makes the base branch OBSERVABLE. Two branches sit at
# different commits, so the scope computed against one is a different set of
# files from the scope computed against the other:
#
#   S ── M (master, edits :core:other) ── F (feature, edits :core:domain)
#   └─ release
#
# From F, the merge base with `master` is M, so only :core:domain changed. The
# merge base with `release` is S, so :core:other changed too. Nothing but the
# value in prism.json distinguishes the two runs.
bb_repo=$(prism_fixture_repo)
bb_commit() {
    git -C "$bb_repo" -c user.email=fixture@example.com -c user.name=fixture \
        add -A
    git -C "$bb_repo" -c user.email=fixture@example.com -c user.name=fixture \
        -c commit.gpgsign=false commit -qm "$1"
}
git -C "$bb_repo" branch release
printf 'class Other { val onMaster = 1 }\n' \
    > "$bb_repo/core/other/src/main/java/Other.kt"
bb_commit 'master advances past release'
git -C "$bb_repo" checkout -q -b bb-feature
printf 'class Seed { val onFeature = 1 }\n' \
    > "$bb_repo/core/domain/src/main/java/Seed.kt"
bb_commit 'the feature edits core/domain'

BB_SCOPE="$bb_repo/.prism/verify/scope.sh"

# Asserted through scope.sh rather than through the helper it calls, because the
# claim is about what a GATE decides to verify, not about what a shell function
# returns. scope.sh prints the same MODULES line the Stop gate derives.
# The MODULES line, not the whole report. SCOPE widens MODULES to its
# dependents, and :core:other depends on :core:domain in this fixture — so
# SCOPE names :core:other under either base branch, and asserting against it
# would pass whatever the configuration said.
bb_modules() {
    prism_fixture_config "$bb_repo" "$1"
    PRISM_ROOT="$bb_repo" sh "$BB_SCOPE" 2>&1 | grep '^MODULES'
}

bb_out=$(bb_modules master)
assert_contains "$bb_out" ":core:domain" \
    "with baseBranch=master the scope contains the module the feature edited"
assert_not_contains "$bb_out" ":core:other" \
    "and not the module master itself changed"

bb_out=$(bb_modules release)
assert_contains "$bb_out" ":core:other" \
    "moving baseBranch to an earlier branch widens the scope to reach it"
assert_contains "$bb_out" ":core:domain" \
    "and still contains the module the feature edited"

# The value is read, not merely present: a branch that does not exist must fail
# to resolve rather than quietly behaving like the old `master` default.
prism_fixture_config "$bb_repo" no-such-branch-at-all
bb_out=$(PRISM_ROOT="$bb_repo" sh "$BB_SCOPE" 2>&1)
assert_contains "$bb_out" "ERROR could not resolve base branch" \
    "a configured branch that does not exist is an error, not a fallback"
assert_contains "$bb_out" "no-such-branch-at-all" \
    "and the error names the ref it was told to use"

# --- the environment overrides, and only overrides -----------------------
#
# scope.sh's --base= and the suite itself both set PRISM_BASE_BRANCH directly.
# An override may redirect the value; it may not remove the requirement for one.
prism_fixture_config "$bb_repo" no-such-branch-at-all
bb_out=$(PRISM_ROOT="$bb_repo" sh "$BB_SCOPE" --base=master 2>&1)
assert_contains "$bb_out" ":core:domain" \
    "--base= overrides an unusable configured branch"

# --- an unreadable configuration DENIES, and never falls back ------------
#
# Three ways to have no answer, all of which used to be indistinguishable from
# "use master": no file, a file that will not parse, and a file that parses but
# is missing the key. Per lib/config.sh's contract, an inability to ask the
# question is not an answer of "no" — so each denies and says which.
#
# Driven through push-gate.sh because a DENIAL is a gate's verdict, not a
# library's return code, and because the push gate is the one that would
# otherwise have let a whole branch onto a remote unverified.
bb_gate_repo=$(prism_fixture_repo)
prism_fixture_branch_commit "$bb_gate_repo"
BB_GATE="$bb_gate_repo/.prism/verify/push-gate.sh"
bb_event() {
    printf '{"tool_name":"Bash","tool_input":{"command":"git push"}}'
}

# A control run first. Without it the three denials below would pass just as
# well against a gate that denies every push for some unrelated reason.
bb_out=$(bb_event | PRISM_ROOT="$bb_gate_repo" PRISM_DRY_RUN=1 \
    sh "$BB_GATE" 2>&1)
assert_contains "$bb_out" "push range:" \
    "with a configured base branch the gate resolves a range"

rm -f "$bb_gate_repo/.prism/prism.json"
bb_out=$(bb_event | PRISM_ROOT="$bb_gate_repo" PRISM_DRY_RUN=1 \
    sh "$BB_GATE" 2>&1)
assert_eq "2" "$?" "an absent prism.json denies the push"
assert_contains "$bb_out" "no base branch is configured" \
    "and the denial says the configuration is what is missing"
assert_not_contains "$bb_out" "push range:" \
    "an absent prism.json never falls back to a built-in default"

printf '{ this is not json\n' > "$bb_gate_repo/.prism/prism.json"
bb_out=$(bb_event | PRISM_ROOT="$bb_gate_repo" PRISM_DRY_RUN=1 \
    sh "$BB_GATE" 2>&1)
assert_eq "2" "$?" "an unparseable prism.json denies the push"
assert_contains "$bb_out" "could not be parsed" \
    "and the denial says the file would not parse"
assert_not_contains "$bb_out" "push range:" \
    "an unparseable prism.json never falls back to a built-in default"

printf '{ "coverage": { "defaultThreshold": 80 } }\n' \
    > "$bb_gate_repo/.prism/prism.json"
bb_out=$(bb_event | PRISM_ROOT="$bb_gate_repo" PRISM_DRY_RUN=1 \
    sh "$BB_GATE" 2>&1)
assert_eq "2" "$?" "a prism.json missing baseBranch denies the push"
assert_contains "$bb_out" "baseBranch is not set" \
    "and the denial names the key that is missing"
assert_not_contains "$bb_out" "push range:" \
    "a missing key never falls back to a built-in default"

printf '{ "baseBranch": "" }\n' > "$bb_gate_repo/.prism/prism.json"
bb_out=$(bb_event | PRISM_ROOT="$bb_gate_repo" PRISM_DRY_RUN=1 \
    sh "$BB_GATE" 2>&1)
assert_eq "2" "$?" "an empty baseBranch denies the push"
assert_contains "$bb_out" "empty" \
    "and the denial says the value is empty rather than missing"

# --- the shipped template renders unattended -----------------------------
#
# The consumer side of the same knob. It is a DEFAULTED parameter, so a bare
# install must produce a usable prism.json with no value supplied — and the
# default must be PRISM's own, never the origin repository's.
bb_rendered=$(mktemp -d)/prism.json
python3 "$HOOK_DIR/lib/render.py" \
    --parameters "$HOOK_DIR/../templates/parameters.json" \
    --template "$HOOK_DIR/../templates/prism.json.in" \
    --output "$bb_rendered" >/dev/null 2>&1
assert_eq "0" "$?" "prism.json.in renders with no values supplied"
assert_eq "main" \
    "$(PRISM_CONFIG="$bb_rendered" prism_config_get baseBranch)" \
    "and the shipped default is main"

rm -rf "$(dirname "$bb_rendered")"

# --- one knob, and the count is the assertion ----------------------------
#
# Phase 2 moves the other eight into prism.json. Until it does, a second key
# appearing on the production read path means one of them arrived early and
# without the tests that were supposed to come with it — which is how a
# configuration surface grows a value nobody proved is read.
#
# Deliberately a tripwire, not a rule: Phase 2 is expected to change this
# number, and changing it should require saying so.
bb_readers=$(grep -rn 'prism_config_get' "$HOOK_DIR"/*.sh "$HOOK_DIR"/lib/*.sh \
    | grep -v 'lib/config.sh' | wc -l | tr -d ' ')
assert_eq "1" "$bb_readers" \
    "exactly one production site reads prism.json, and it is the proof knob"

# --- and `posture` is not one of them ------------------------------------
#
# The machine-checkable form of the claim prism.json.in makes in prose. A
# posture key is permitted ONLY because it is a record rather than a switch: no
# gate consults it, so no combination of configuration values leaves a gate
# unrun, and R7's no-escape-hatch invariant is untouched.
#
# The day a gate reads it, that argument is gone and this assertion is what
# says so -- before the key has quietly become the thing it was written not to
# be. prism-doctor is deliberately outside this glob: it has no .sh extension,
# it is a reporting tool that performs nothing, and reading the record is its
# entire job.
#
# ANCHORED TO A READ, NOT TO THE WORD. This used to grep for `posture`
# anywhere in a gate script, which made it fire on a COMMENT that merely used
# the word -- and the fix for that would have been to reword the prose, i.e. to
# satisfy a grep rather than the property. The property is that no gate
# consults the value: through prism_config_get, through a JSON read, or through
# an environment variable. Prose may say the word; code may not fetch it.
bb_posture=$(grep -rlE "prism_config_get[[:space:]]+posture|['\"]posture['\"]|PRISM_POSTURE" \
    "$HOOK_DIR"/*.sh "$HOOK_DIR"/lib/*.sh 2>/dev/null | wc -l | tr -d ' ')
assert_eq "0" "$bb_posture" \
    "no gate reads the posture record — it is a record, not a switch"

rm -rf "$bb_repo" "$bb_gate_repo"
